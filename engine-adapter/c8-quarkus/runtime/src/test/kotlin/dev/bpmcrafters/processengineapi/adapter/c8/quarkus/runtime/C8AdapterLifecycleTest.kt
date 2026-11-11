package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskType
import dev.bpmcrafters.processengineapi.task.TaskHandler
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.CompleteJobCommandStep1
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.response.CompleteJobResponse
import io.camunda.client.api.response.UserTaskProperties
import io.camunda.client.api.search.enums.JobKind
import io.camunda.client.api.search.enums.ListenerEventType
import io.camunda.client.api.worker.JobClient
import io.camunda.client.api.worker.JobHandler
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration

class C8AdapterLifecycleTest {

  @Test
  fun `custom strategies do not start or stop application deliveries`() {
    val fixture = Fixture("CUSTOM", "CUSTOM", emptyMap())
    fixture.lifecycle.start(null)
    fixture.lifecycle.stop(null)
    verifyNoInteractions(fixture.service, fixture.pull, fixture.listener, fixture.refresh, fixture.registration, fixture.executor)
  }

  @Test
  fun `scheduled iterations continue after exceptions and stop before workers`() {
    val fixture = Fixture("SUBSCRIPTION", "SCHEDULED", emptyMap())
    doThrow(IllegalStateException("one iteration")).doNothing().whenever(fixture.pull).refresh()
    fixture.lifecycle.start(null)
    val iteration = fixture.iteration()
    iteration.run()
    iteration.run()
    verify(fixture.pull, times(2)).refresh()
    fixture.lifecycle.stop(null)
    val order = inOrder(fixture.future, fixture.executor, fixture.service)
    order.verify(fixture.future).cancel(false)
    order.verify(fixture.executor).shutdown()
    order.verify(fixture.executor).awaitTermination(30, TimeUnit.SECONDS)
    order.verify(fixture.service).close()
  }

  @Test
  fun `real scheduler finishes in flight refresh before closing workers and cancels later iterations`() {
    val fixture = Fixture("SUBSCRIPTION", "SCHEDULED", emptyMap())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val iterations = AtomicInteger()
    doAnswer {
      iterations.incrementAndGet()
      entered.countDown()
      assertThat(release.await(30, TimeUnit.SECONDS)).isTrue()
      finished.countDown()
      null
    }.whenever(fixture.pull).refresh()
    doAnswer {
      assertThat(finished.count).`as`("refresh finished before worker cleanup").isZero()
      null
    }.whenever(fixture.service).close()

    val scheduler = ShutdownSignallingExecutor()
    val stopper = Executors.newSingleThreadExecutor()
    try {
      doReturn(scheduler).whenever(fixture.lifecycle).newExecutor()
      try {
        fixture.lifecycle.start(null)
        assertThat(entered.await(5, TimeUnit.SECONDS)).`as`("refresh entered").isTrue()
        val stopping = stopper.submit(Runnable { fixture.lifecycle.stop(null) })
        try {
          assertThat(scheduler.shutdownStarted.await(5, TimeUnit.SECONDS)).`as`("scheduler shutdown started").isTrue()
          assertThat(stopping.isDone).isFalse()
          verify(fixture.service, never()).close()
          release.countDown()
          stopping.get(5, TimeUnit.SECONDS)
          verify(fixture.service).close()
          assertThat(finished.count).isZero()
          assertThat(iterations.get()).isEqualTo(1)
          assertThat(scheduler.delivery.isCancelled).isTrue()
          assertThat(scheduler.isTerminated).isTrue()
          assertThat(scheduler.queue).isEmpty()
          assertThat(scheduler.completedTaskCount).isEqualTo(1)
        } finally {
          release.countDown()
          stopping.cancel(true)
        }
      } finally {
        release.countDown()
        fixture.lifecycle.stop(null)
      }
    } finally {
      try {
        stopper.shutdownNow()
        assertThat(stopper.awaitTermination(5, TimeUnit.SECONDS)).`as`("stopper terminated").isTrue()
      } finally {
        scheduler.shutdownNow()
        assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).`as`("scheduler terminated").isTrue()
      }
    }
  }

  @Test
  fun `stop drains real service callbacks before application disposal and rejects queued callbacks`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workerClosed = CountDownLatch(1)
    val actions = AtomicInteger()
    val disposed = AtomicInteger()
    val repository = InMemSubscriptionRepository().apply {
      createTaskSubscription(TaskSubscriptionHandle(
        taskType = TaskType.EXTERNAL,
        taskDescriptionKey = "service-topic",
        restrictions = emptyMap(),
        payloadDescription = null,
        action = { _, _ ->
          actions.incrementAndGet()
          entered.countDown()
          check(release.await(30, TimeUnit.SECONDS)) { "handler was not released" }
          assertThat(disposed.get()).`as`("application remains alive during callback").isZero()
        },
        termination = { _ -> }
      ))
    }
    val client = mock<CamundaClient>()
    val worker = mock<JobWorker>()
    val builder = mock<JobWorkerBuilderStep1>()
    val builder2 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
    val builder3 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>()
    val handler = argumentCaptor<JobHandler>()
    whenever(client.newWorker()).thenReturn(builder)
    whenever(builder.jobType("service-topic")).thenReturn(builder2)
    whenever(builder2.handler(handler.capture())).thenReturn(builder3)
    whenever(builder3.name(any())).thenReturn(builder3)
    whenever(builder3.timeout(any<Long>())).thenReturn(builder3)
    whenever(builder3.open()).thenReturn(worker)
    doAnswer { workerClosed.countDown(); null }.whenever(worker).close()
    val service = SubscribingServiceTaskDelivery(client, repository, "test-worker", 10, 60)
    val fixture = Fixture("SUBSCRIPTION", "CUSTOM", emptyMap(), service)
    val job = mock<ActivatedJob> {
      whenever(it.key).thenReturn(1234L)
      whenever(it.type).thenReturn("service-topic")
      whenever(it.variablesAsMap).thenReturn(emptyMap())
      whenever(it.customHeaders).thenReturn(emptyMap())
    }
    val jobClient = mock<JobClient>()
    val callbacks = Executors.newSingleThreadExecutor()
    val stopper = Executors.newSingleThreadExecutor()
    try {
      fixture.lifecycle.start(null)
      val admitted = callbacks.submit(Runnable { handler.firstValue.handle(jobClient, job) })
      assertThat(entered.await(5, TimeUnit.SECONDS)).`as`("application handler entered").isTrue()
      val queued = callbacks.submit(Runnable { handler.firstValue.handle(jobClient, job) })
      val stopping = stopper.submit(Runnable {
        fixture.lifecycle.stop(null)
        disposed.incrementAndGet()
      })
      assertThat(workerClosed.await(5, TimeUnit.SECONDS)).`as`("worker acquisition stopped").isTrue()
      assertThatThrownBy { stopping.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
      assertThat(disposed.get()).isZero()
      release.countDown()
      admitted.get(5, TimeUnit.SECONDS)
      stopping.get(5, TimeUnit.SECONDS)
      queued.get(5, TimeUnit.SECONDS)
      callbacks.submit(Runnable { handler.firstValue.handle(jobClient, job) }).get(5, TimeUnit.SECONDS)
      assertThat(actions.get()).isEqualTo(1)
      assertThat(disposed.get()).isEqualTo(1)
      verify(client).newWorker()
      verifyNoMoreInteractions(client)
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      stopper.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
      stopper.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `stop drains real listener callbacks including completion before application disposal`() {
    assertUserCallbacksDrain(listenerStrategy = true)
  }

  @Test
  fun `stop drains real refreshing callbacks before application disposal and rejects queued callbacks`() {
    assertUserCallbacksDrain(listenerStrategy = false)
  }

  private fun assertUserCallbacksDrain(listenerStrategy: Boolean) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val completionEntered = CountDownLatch(1)
    val completionRelease = CountDownLatch(1)
    val workerClosed = CountDownLatch(1)
    val actions = AtomicInteger()
    val disposed = AtomicInteger()
    val repository = spy(InMemSubscriptionRepository())
    repository.createTaskSubscription(TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ ->
        actions.incrementAndGet()
        entered.countDown()
        check(release.await(30, TimeUnit.SECONDS)) { "handler was not released" }
        assertThat(disposed.get()).`as`("application remains alive during callback").isZero()
      },
      termination = { _ -> }
    ))
    val client = mock<CamundaClient>()
    val worker = mock<JobWorker>()
    val builder = mock<JobWorkerBuilderStep1>()
    val builder2 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
    val builder3 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>()
    val handlers = argumentCaptor<JobHandler>()
    whenever(client.newWorker()).thenReturn(builder)
    whenever(builder.jobType(any())).thenReturn(builder2)
    whenever(builder2.handler(handlers.capture())).thenReturn(builder3)
    whenever(builder3.name(any())).thenReturn(builder3)
    whenever(builder3.maxJobsActive(any())).thenReturn(builder3)
    whenever(builder3.streamEnabled(any())).thenReturn(builder3)
    whenever(builder3.timeout(any<Long>())).thenReturn(builder3)
    whenever(builder3.open()).thenReturn(worker)
    doAnswer { workerClosed.countDown(); null }.whenever(worker).close()
    val completion = mock<CompleteJobCommandStep1>()
    val completed = mock<CamundaFuture<CompleteJobResponse>>()
    whenever(client.newCompleteCommand(1234L)).thenReturn(completion)
    whenever(completion.send()).thenReturn(completed)
    whenever(completed.join()).thenAnswer {
      completionEntered.countDown()
      check(completionRelease.await(30, TimeUnit.SECONDS)) { "completion was not released" }
      assertThat(disposed.get()).`as`("application remains alive through SDK completion").isZero()
      null
    }
    val fixture = if (listenerStrategy) {
      Fixture("CUSTOM", "LISTENER", emptyMap(), listener = ListenerUserTaskDelivery(client, repository))
    } else {
      Fixture("CUSTOM", "SUBSCRIPTION_REFRESHING", emptyMap(),
        refresh = SubscribingRefreshingZeebeJobUserTaskDelivery(client, repository, "test-worker", 60000L))
    }
    val userTask = mock<UserTaskProperties> {
      whenever(it.userTaskKey).thenReturn(5678L)
    }
    val job = mock<ActivatedJob> {
      whenever(it.key).thenReturn(1234L)
      whenever(it.elementId).thenReturn("user-task")
      whenever(it.variablesAsMap).thenReturn(emptyMap())
      whenever(it.customHeaders).thenReturn(emptyMap())
      if (listenerStrategy) {
        whenever(it.kind).thenReturn(JobKind.TASK_LISTENER)
        whenever(it.listenerEventType).thenReturn(ListenerEventType.CREATING)
        whenever(it.userTask).thenReturn(userTask)
      }
    }
    val jobClient = mock<JobClient>()
    val callbacks = Executors.newSingleThreadExecutor()
    val stopper = Executors.newSingleThreadExecutor()
    try {
      fixture.lifecycle.start(null)
      val admitted = callbacks.submit(Runnable { handlers.firstValue.handle(jobClient, job) })
      assertThat(entered.await(5, TimeUnit.SECONDS)).`as`("application handler entered").isTrue()
      val queued = callbacks.submit(Runnable { handlers.firstValue.handle(jobClient, job) })
      val stopping = stopper.submit(Runnable {
        fixture.lifecycle.stop(null)
        disposed.incrementAndGet()
      })
      assertThat(workerClosed.await(5, TimeUnit.SECONDS)).`as`("worker acquisition stopped").isTrue()
      assertThatThrownBy { stopping.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
      assertThat(disposed.get()).isZero()
      release.countDown()
      if (listenerStrategy) {
        assertThat(completionEntered.await(5, TimeUnit.SECONDS)).`as`("SDK completion entered").isTrue()
        assertThatThrownBy { stopping.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
        assertThat(disposed.get()).isZero()
        completionRelease.countDown()
      }
      admitted.get(5, TimeUnit.SECONDS)
      stopping.get(5, TimeUnit.SECONDS)
      queued.get(5, TimeUnit.SECONDS)
      verify(client).newWorker()
      if (listenerStrategy) {
        verify(client).newCompleteCommand(1234L)
        verify(completion).send()
        verify(completed).join()
      }
      verifyNoMoreInteractions(client)
      clearInvocations(repository)
      callbacks.submit(Runnable { handlers.firstValue.handle(jobClient, job) }).get(5, TimeUnit.SECONDS)
      verifyNoInteractions(repository)
      verifyNoMoreInteractions(client)
      assertThat(actions.get()).isEqualTo(1)
      assertThat(disposed.get()).isEqualTo(1)
      verify(worker).close()
    } finally {
      release.countDown()
      completionRelease.countDown()
      callbacks.shutdown()
      stopper.shutdown()
      assertThat(callbacks.awaitTermination(5, TimeUnit.SECONDS)).`as`("callbacks terminated").isTrue()
      assertThat(stopper.awaitTermination(5, TimeUnit.SECONDS)).`as`("stopper terminated").isTrue()
    }
  }

  @Test
  fun `real service timeout still closes listener and reports incomplete cleanup`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val real = RealServiceFixture(TaskHandler { _, _ ->
      entered.countDown()
      check(release.await(30, TimeUnit.SECONDS))
    }, Duration.ofMillis(30))
    val fixture = Fixture("SUBSCRIPTION", "LISTENER", emptyMap(), real.service)
    val callbacks = Executors.newSingleThreadExecutor()
    val stopper = Executors.newSingleThreadExecutor()
    val job = real.job()
    val client = mock<JobClient>()
    try {
      fixture.lifecycle.start(null)
      val callback = callbacks.submit(Runnable { real.handlers.firstValue.handle(client, job) })
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
      val stopping = stopper.submit(Runnable { fixture.lifecycle.stop(null) })
      assertThatThrownBy { stopping.get(5, TimeUnit.SECONDS) }
        .hasCauseInstanceOf(IllegalStateException::class.java)
        .hasStackTraceContaining("did not drain")
      verify(real.worker).close()
      verify(fixture.listener).close()
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      real.service.close()
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      stopper.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
      stopper.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `draining handler can reenter lifecycle stop without blocking on lifecycle lock`() {
    val entered = CountDownLatch(1)
    val closing = CountDownLatch(1)
    val release = CountDownLatch(1)
    lateinit var fixture: Fixture
    val real = RealServiceFixture(TaskHandler { _, _ ->
      entered.countDown()
      check(release.await(30, TimeUnit.SECONDS))
      assertThatThrownBy { fixture.lifecycle.stop(null) }.hasMessageContaining("already in progress")
    })
    doAnswer { closing.countDown(); null }.whenever(real.worker).close()
    fixture = Fixture("SUBSCRIPTION", "CUSTOM", emptyMap(), real.service)
    val callbacks = Executors.newSingleThreadExecutor()
    val stopper = Executors.newSingleThreadExecutor()
    val job = real.job()
    val client = mock<JobClient>()
    try {
      fixture.lifecycle.start(null)
      val callback = callbacks.submit(Runnable { real.handlers.firstValue.handle(client, job) })
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
      val stopping = stopper.submit(Runnable { fixture.lifecycle.stop(null) })
      assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue()
      assertThatThrownBy { stopping.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      stopping.get(5, TimeUnit.SECONDS)
      fixture.lifecycle.stop(null)
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      stopper.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
      stopper.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `startup rollback releases lifecycle lock before draining reentrant service callback`() {
    val entered = CountDownLatch(1)
    val workerClosed = CountDownLatch(1)
    val startupFailure = IllegalStateException("listener startup failed")
    lateinit var fixture: Fixture
    val real = RealServiceFixture(TaskHandler { _, _ ->
      entered.countDown()
      check(workerClosed.await(5, TimeUnit.SECONDS)) { "rollback did not close the worker" }
      assertThatThrownBy { fixture.lifecycle.stop(null) }.hasMessageContaining("already in progress")
    }, Duration.ofSeconds(1))
    fixture = Fixture("SUBSCRIPTION", "LISTENER", emptyMap(), real.service)
    doAnswer { workerClosed.countDown(); null }.whenever(real.worker).close()
    val callbacks = Executors.newSingleThreadExecutor()
    val starter = Executors.newSingleThreadExecutor()
    val job = real.job()
    val client = mock<JobClient>()
    lateinit var callback: Future<*>
    doAnswer {
      callback = callbacks.submit(Runnable { real.handlers.firstValue.handle(client, job) })
      check(entered.await(5, TimeUnit.SECONDS)) { "service callback did not enter" }
      throw startupFailure
    }.whenever(fixture.listener).subscribe()
    try {
      val starting = starter.submit(Runnable { fixture.lifecycle.start(null) })
      assertThatThrownBy { starting.get(5, TimeUnit.SECONDS) }.hasCause(startupFailure)
      verify(real.worker).close()
      verify(fixture.listener).close()
      verify(real.client).newWorker()
      verifyNoMoreInteractions(real.client)
      assertThat(startupFailure.suppressed).`as`("rollback drains without a cleanup timeout").isEmpty()
      callback.get(5, TimeUnit.SECONDS)
    } finally {
      workerClosed.countDown()
      callbacks.shutdownNow()
      starter.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
      starter.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `periodic fatal errors are not swallowed`() {
    val fixture = Fixture("CUSTOM", "SCHEDULED", emptyMap())
    val fatal = AssertionError("fatal")
    doThrow(fatal).whenever(fixture.pull).refresh()
    fixture.lifecycle.start(null)
    assertThatThrownBy { fixture.iteration().run() }.isSameAs(fatal)
    fixture.lifecycle.stop(null)
  }

  @Test
  fun `starts and stops only once`() {
    val fixture = Fixture("SUBSCRIPTION", "SCHEDULED", emptyMap())
    fixture.lifecycle.start(null)
    fixture.lifecycle.start(null)
    fixture.lifecycle.stop(null)
    fixture.lifecycle.stop(null)
    fixture.lifecycle.start(null)
    verify(fixture.service).subscribe()
    verify(fixture.service).close()
    verify(fixture.executor).shutdown()
  }

  @Test
  fun `shutdown before startup does not create resources`() {
    val fixture = Fixture("SUBSCRIPTION", "SCHEDULED", emptyMap())
    fixture.lifecycle.stop(null)
    fixture.lifecycle.start(null)
    verifyNoInteractions(fixture.service, fixture.pull, fixture.executor)
  }

  @Test
  fun `refreshing subscribes before scheduling and unsubscribes after schedule stops`() {
    val fixture = Fixture("CUSTOM", "SUBSCRIPTION_REFRESHING", emptyMap())
    fixture.lifecycle.start(null)
    fixture.iteration().run()
    fixture.lifecycle.stop(null)
    val order = inOrder(fixture.refresh, fixture.executor)
    order.verify(fixture.refresh).subscribe()
    order.verify(fixture.executor).scheduleAtFixedRate(any<Runnable>(), eq(0L), eq(5L), eq(TimeUnit.SECONDS))
    order.verify(fixture.refresh).refresh()
    order.verify(fixture.executor).shutdown()
    order.verify(fixture.executor).awaitTermination(30, TimeUnit.SECONDS)
    order.verify(fixture.refresh).close()
  }

  @Test
  fun `listener registers then preloads then subscribes without polling`() {
    val fixture = Fixture("SUBSCRIPTION", "LISTENER", emptyMap())
    fixture.lifecycle.start(null)
    val order = inOrder(fixture.registration, fixture.pull, fixture.listener)
    order.verify(fixture.registration).registerIfEnabled()
    order.verify(fixture.pull).refresh()
    order.verify(fixture.listener).subscribe()
    verifyNoInteractions(fixture.executor)
    fixture.lifecycle.stop(null)
    verify(fixture.service).close()
    verify(fixture.listener).close()
  }

  @Test
  fun `listener can skip preload`() {
    val fixture = Fixture("CUSTOM", "LISTENER", mapOf("user-tasks.listener.preload-existing-tasks" to "false"))
    fixture.lifecycle.start(null)
    verifyNoInteractions(fixture.pull)
    verify(fixture.listener).subscribe()
    fixture.lifecycle.stop(null)
  }

  @Test
  fun `listener preload exception still subscribes`() {
    val fixture = Fixture("CUSTOM", "LISTENER", emptyMap())
    doThrow(IllegalStateException("preload")).whenever(fixture.pull).refresh()
    fixture.lifecycle.start(null)
    verify(fixture.listener).subscribe()
    fixture.lifecycle.stop(null)
  }

  @Test
  fun `listener registration failure prevents preload and subscription and cleans up`() {
    val fixture = Fixture("SUBSCRIPTION", "LISTENER", emptyMap())
    val failure = IllegalStateException("registration")
    doThrow(failure).whenever(fixture.registration).registerIfEnabled()
    assertThatThrownBy { fixture.lifecycle.start(null) }.isSameAs(failure)
    verifyNoInteractions(fixture.pull)
    verify(fixture.listener, never()).subscribe()
    verify(fixture.listener).close()
    verify(fixture.service).close()
  }

  @Test
  fun `preload fatal error prevents subscription and is rethrown after cleanup`() {
    val fixture = Fixture("SUBSCRIPTION", "LISTENER", emptyMap())
    val fatal = AssertionError("preload fatal")
    doThrow(fatal).whenever(fixture.pull).refresh()
    assertThatThrownBy { fixture.lifecycle.start(null) }.isSameAs(fatal)
    verify(fixture.listener, never()).subscribe()
    verify(fixture.listener).close()
    verify(fixture.service).close()
  }

  @Test
  fun `partial service startup failure closes workers and suppresses cleanup failure`() {
    val fixture = Fixture("SUBSCRIPTION", "SCHEDULED", emptyMap())
    val failure = IllegalStateException("subscribe")
    val closingFailure = IllegalStateException("close")
    doThrow(failure).whenever(fixture.service).subscribe()
    doThrow(closingFailure).whenever(fixture.service).close()
    assertThatThrownBy { fixture.lifecycle.start(null) }.isSameAs(failure)
    assertThat(failure.suppressed).hasSize(1)
    assertThat(failure.suppressed.single()).hasCause(closingFailure)
    verifyNoInteractions(fixture.pull, fixture.executor)
    fixture.lifecycle.stop(null)
    verify(fixture.service).close()
  }

  @Test
  fun `listener startup failure closes both components`() {
    val fixture = Fixture("SUBSCRIPTION", "LISTENER", emptyMap())
    doThrow(IllegalStateException("subscribe")).whenever(fixture.listener).subscribe()
    assertThatIllegalStateException().isThrownBy { fixture.lifecycle.start(null) }
    verify(fixture.service).close()
    verify(fixture.listener).close()
  }

  @Test
  fun `refreshing startup failure unsubscribes`() {
    val fixture = Fixture("SUBSCRIPTION", "SUBSCRIPTION_REFRESHING", emptyMap())
    doThrow(IllegalStateException("subscribe")).whenever(fixture.refresh).subscribe()
    assertThatIllegalStateException().isThrownBy { fixture.lifecycle.start(null) }
    verify(fixture.refresh).close()
    verify(fixture.service).close()
    verifyNoInteractions(fixture.executor)
  }

  @Test
  fun `scheduling failure stops executor and workers`() {
    val fixture = Fixture("SUBSCRIPTION", "SCHEDULED", emptyMap())
    whenever(fixture.executor.scheduleAtFixedRate(any<Runnable>(), any(), any(), any()))
      .thenThrow(IllegalStateException("rejected"))
    assertThatIllegalStateException().isThrownBy { fixture.lifecycle.start(null) }
    verify(fixture.executor).shutdown()
    verify(fixture.service).close()
  }

  @Test
  fun `cleanup attempts both components and aggregates failures`() {
    val fixture = Fixture("SUBSCRIPTION", "LISTENER", emptyMap())
    val serviceFailure = IllegalStateException("service close")
    val listenerFailure = IllegalStateException("listener close")
    fixture.lifecycle.start(null)
    doThrow(serviceFailure).whenever(fixture.service).close()
    doThrow(listenerFailure).whenever(fixture.listener).close()
    assertThatIllegalStateException().isThrownBy { fixture.lifecycle.stop(null) }.withCause(serviceFailure)
    assertThat(serviceFailure.suppressed).containsExactly(listenerFailure)
    verify(fixture.listener).close()
  }

  @Test
  fun `cleanup fatal error still attempts remaining components`() {
    val fixture = Fixture("SUBSCRIPTION", "LISTENER", emptyMap())
    val fatal = AssertionError("close fatal")
    fixture.lifecycle.start(null)
    doThrow(fatal).whenever(fixture.service).close()
    assertThatThrownBy { fixture.lifecycle.stop(null) }.isSameAs(fatal)
    verify(fixture.listener).close()
  }

  @Test
  fun `waits then interrupts stuck schedule`() {
    val fixture = Fixture("SUBSCRIPTION", "SCHEDULED", emptyMap())
    whenever(fixture.executor.awaitTermination(30, TimeUnit.SECONDS)).thenReturn(false, true)
    fixture.lifecycle.start(null)
    fixture.lifecycle.stop(null)
    verify(fixture.executor).shutdownNow()
    verify(fixture.executor, times(2)).awaitTermination(30, TimeUnit.SECONDS)
    verify(fixture.service).close()
  }

  @Test
  fun `schedule timeout does not skip worker cleanup`() {
    val fixture = Fixture("SUBSCRIPTION", "SCHEDULED", emptyMap())
    whenever(fixture.executor.awaitTermination(30, TimeUnit.SECONDS)).thenReturn(false)
    fixture.lifecycle.start(null)
    assertThatIllegalStateException().isThrownBy { fixture.lifecycle.stop(null) }
      .withCauseInstanceOf(IllegalStateException::class.java)
    verify(fixture.service).close()
  }

  @Test
  fun `interrupted shutdown restores interrupt and closes workers`() {
    val fixture = Fixture("SUBSCRIPTION", "SCHEDULED", emptyMap())
    whenever(fixture.executor.awaitTermination(30, TimeUnit.SECONDS)).thenThrow(InterruptedException("stop"))
    fixture.lifecycle.start(null)
    try {
      assertThatIllegalStateException().isThrownBy { fixture.lifecycle.stop(null) }
        .withCauseInstanceOf(InterruptedException::class.java)
      assertThat(Thread.currentThread().isInterrupted).isTrue()
      verify(fixture.executor).shutdownNow()
      verify(fixture.service).close()
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `creates an owned named executor at runtime`() {
    val config = ConfigTestData.enabled("CUSTOM", "CUSTOM", emptyMap())
    val lifecycle = C8AdapterLifecycle(config.build, config.runtime, mock(), mock(), mock(), mock(), mock())
    val executor = lifecycle.newExecutor()
    try {
      assertThat(executor.submit<String> { Thread.currentThread().name }.get(5, TimeUnit.SECONDS))
        .isEqualTo("C8-ADAPTER-USER-TASKS")
    } finally {
      executor.shutdownNow()
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).`as`("owned executor terminated").isTrue()
    }
  }

  @Test
  fun `startup and cleanup may throw the same fatal instance`() {
    val fixture = Fixture("SUBSCRIPTION", "CUSTOM", emptyMap())
    val fatal = AssertionError("same fatal")
    doThrow(fatal).whenever(fixture.service).subscribe()
    doThrow(fatal).whenever(fixture.service).close()
    assertThatThrownBy { fixture.lifecycle.start(null) }.isSameAs(fatal)
    assertThat(fatal.suppressed).isEmpty()
  }

  @Test
  fun `cleanup can aggregate the same failure without self suppression`() {
    val fixture = Fixture("SUBSCRIPTION", "LISTENER", emptyMap())
    val failure = IllegalStateException("same close failure")
    fixture.lifecycle.start(null)
    doThrow(failure).whenever(fixture.service).close()
    doThrow(failure).whenever(fixture.listener).close()
    assertThatIllegalStateException().isThrownBy { fixture.lifecycle.stop(null) }.withCause(failure)
    assertThat(failure.suppressed).isEmpty()
    verify(fixture.listener).close()
  }

  private class RealServiceFixture(action: TaskHandler, timeout: Duration = Duration.ofSeconds(30)) {
    val client = mock<CamundaClient>()
    val worker = mock<JobWorker>()
    val handlers = argumentCaptor<JobHandler>()
    val service: SubscribingServiceTaskDelivery

    init {
      val repository = InMemSubscriptionRepository().apply {
        createTaskSubscription(TaskSubscriptionHandle(
          taskType = TaskType.EXTERNAL,
          taskDescriptionKey = "service-topic",
          restrictions = emptyMap(),
          payloadDescription = null,
          action = action,
          termination = { _ -> }
        ))
      }
      val builder = mock<JobWorkerBuilderStep1>()
      val builder2 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
      val builder3 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>()
      whenever(client.newWorker()).thenReturn(builder)
      whenever(builder.jobType("service-topic")).thenReturn(builder2)
      whenever(builder2.handler(handlers.capture())).thenReturn(builder3)
      whenever(builder3.name(any())).thenReturn(builder3)
      whenever(builder3.timeout(any<Long>())).thenReturn(builder3)
      whenever(builder3.open()).thenReturn(worker)
      service = SubscribingServiceTaskDelivery(client, repository, "test-worker", 10, 60, timeout)
    }

    fun job(): ActivatedJob = mock {
      whenever(it.key).thenReturn(1234L)
      whenever(it.type).thenReturn("service-topic")
      whenever(it.variablesAsMap).thenReturn(emptyMap())
      whenever(it.customHeaders).thenReturn(emptyMap())
    }
  }

  private class ShutdownSignallingExecutor : ScheduledThreadPoolExecutor(1) {
    val shutdownStarted = CountDownLatch(1)
    lateinit var delivery: ScheduledFuture<*>

    override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> {
      delivery = super.scheduleAtFixedRate(command, initialDelay, period, unit)
      return delivery
    }

    override fun shutdown() {
      super.shutdown()
      shutdownStarted.countDown()
    }
  }

  private class Fixture(
    serviceStrategy: String,
    userStrategy: String,
    settings: Map<String, String>,
    val service: SubscribingServiceTaskDelivery = mock(),
    val refresh: SubscribingRefreshingZeebeJobUserTaskDelivery = mock(),
    val listener: ListenerUserTaskDelivery = mock()
  ) {
    val pull = mock<PullUserTaskDelivery>()
    val registration = mock<GlobalUserTaskListenerRegistrationHelper>()
    val executor = mock<ScheduledExecutorService>()
    val future = mock<ScheduledFuture<*>>()
    val lifecycle: C8AdapterLifecycle

    init {
      val config = ConfigTestData.enabled(serviceStrategy, userStrategy, settings)
      lifecycle = spy(C8AdapterLifecycle(
        config.build, config.runtime, instance(service), instance(pull), instance(refresh), instance(listener), instance(registration)
      ))
      doReturn(executor).whenever(lifecycle).newExecutor()
      doReturn(future).whenever(executor).scheduleAtFixedRate(any<Runnable>(), any(), any(), any())
      whenever(executor.awaitTermination(any(), any())).thenReturn(true)
    }

    fun iteration(): Runnable {
      val captor = argumentCaptor<Runnable>()
      verify(executor).scheduleAtFixedRate(captor.capture(), eq(0L), eq(5L), eq(TimeUnit.SECONDS))
      return captor.firstValue
    }

    private fun <T : Any> instance(value: T): Instance<T> = mock<Instance<T>>().also {
      whenever(it.get()).thenReturn(value)
    }
  }
}
