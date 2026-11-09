package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.FailJobCommandStep1
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.response.FailJobResponse
import io.camunda.client.api.worker.JobClient
import io.camunda.client.api.worker.JobHandler
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class SubscribingServiceTaskDeliveryTest {

  companion object {
    private const val JOB_KEY = 1234L
    private const val TASK_ID = "1234"
    private const val TOPIC = "test-topic"
    private const val ELEMENT_ID = "activity-1"
    private const val TENANT_ID = "tenant-1"
    private const val PROCESS_INSTANCE_KEY = 333L
    private const val PROCESS_DEFINITION_KEY = 444L
    private const val RETRIES = 3
  }

  // We need an instance of SubscribingServiceTaskDelivery because matches is an extension function
  // on TaskSubscriptionHandle declared inside SubscribingServiceTaskDelivery class.
  private val delivery = SubscribingServiceTaskDelivery(
    camundaClient = mock(),
    subscriptionRepository = mock(),
    workerId = "test-worker",
    retryTimeoutInSeconds = 10,
    lockDurationInSeconds = 60
  )

  @Test
  fun `should match when all restrictions match`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "test-topic",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "activity-1",
        CommonRestrictions.TENANT_ID to "tenant-1"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("test-topic")
      whenever(it.elementId).thenReturn("activity-1")
      whenever(it.tenantId).thenReturn("tenant-1")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should match and ignore workerLockDurationInMilliseconds restriction`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "test-topic",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "activity-1",
        CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS to "5000"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("test-topic")
      whenever(it.elementId).thenReturn("activity-1")
    }

    with(delivery) {
      // It should match even if workerLockDurationInMilliseconds is present in restrictions
      // but not present in ActivatedJob (it's not even a field in ActivatedJob that we check)
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should not match when task type is different`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "test-topic",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("test-topic")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should not match when topic is different`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "test-topic",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("other-topic")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should not match when restriction does not match`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "test-topic",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "activity-1"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("test-topic")
      whenever(it.elementId).thenReturn("activity-2")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should match when taskDescriptionKey is null`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = null,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("any-topic")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should terminate locally cached service task when subscribed delivery fails`() {
    val cachedTasks = linkedMapOf<String, TaskInformation>()
    var termination: TaskInformation? = null
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = TOPIC,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = TaskHandler { taskInformation, _ ->
        cachedTasks[taskInformation.taskId] = taskInformation
        error("boom")
      },
      termination = TaskTerminationHandler { taskInformation ->
        termination = taskInformation
        cachedTasks.remove(taskInformation.taskId)
      }
    )

    val subscriptionRepository = dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository().apply {
      createTaskSubscription(subscription)
    }
    val camundaClient = mock<CamundaClient>()
    val workerBuilder = mock<JobWorkerBuilderStep1>()
    val workerBuilder2 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
    val workerBuilder3 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>()
    val jobWorker = mock<JobWorker>()
    val handler = argumentCaptor<JobHandler>()

    whenever(camundaClient.newWorker()).thenReturn(workerBuilder)
    whenever(workerBuilder.jobType(TOPIC)).thenReturn(workerBuilder2)
    whenever(workerBuilder2.handler(handler.capture())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.name(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.timeout(any<Long>())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.open()).thenReturn(jobWorker)

    val failJobStep1 = mock<FailJobCommandStep1>()
    val failJobStep2 = mock<FailJobCommandStep1.FailJobCommandStep2>()
    val failJobFuture = mock<CamundaFuture<FailJobResponse>>()
    whenever(camundaClient.newFailCommand(JOB_KEY)).thenReturn(failJobStep1)
    whenever(failJobStep1.retries(RETRIES - 1)).thenReturn(failJobStep2)
    whenever(failJobStep2.retryBackoff(Duration.ofSeconds(10))).thenReturn(failJobStep2)
    whenever(failJobStep2.send()).thenReturn(failJobFuture)

    val delivery = SubscribingServiceTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      workerId = "test-worker",
      retryTimeoutInSeconds = 10,
      lockDurationInSeconds = 60
    )

    delivery.subscribe()

    handler.firstValue.handle(mock<JobClient>(), activatedJob())

    assertThat(cachedTasks).isEmpty()
    assertThat(termination?.taskId).isEqualTo(TASK_ID)
    assertThat(termination?.meta).containsEntry(TaskInformation.REASON, TaskInformation.DELETE)
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
  }

  @Test
  fun `should close all registered workers without closing the shared client`() {
    val client = mock<CamundaClient>()
    val firstWorker = mock<JobWorker>()
    val secondWorker = mock<JobWorker>()
    val delivery = deliveryWithWorkers(client, firstWorker, secondWorker)
    delivery.subscribe()
    delivery.subscribe()

    delivery.close()

    verify(firstWorker).close()
    verify(secondWorker).close()
    verify(client, times(2)).newWorker()
    verifyNoMoreInteractions(client)
  }

  @Test
  fun `should not close workers again on repeated cleanup`() {
    val worker = mock<JobWorker>()
    val delivery = deliveryWithWorkers(mock(), worker)
    delivery.subscribe()

    delivery.close()
    delivery.close()

    verify(worker).close()
    verifyNoMoreInteractions(worker)
  }

  @Test
  fun `should safely close without registered workers`() {
    assertThatCode {
      delivery.close()
      delivery.close()
    }.doesNotThrowAnyException()
  }

  @Test
  fun `should attempt every worker cleanup and report failures without retrying closed workers`() {
    val firstWorker = mock<JobWorker>()
    val secondWorker = mock<JobWorker>()
    val thirdWorker = mock<JobWorker>()
    val firstFailure = IllegalStateException("first worker failed to close")
    val secondFailure = IllegalStateException("second worker failed to close")
    doThrow(firstFailure).whenever(firstWorker).close()
    doThrow(secondFailure).whenever(secondWorker).close()
    val delivery = deliveryWithWorkers(mock(), firstWorker, secondWorker, thirdWorker)
    repeat(3) { delivery.subscribe() }

    assertThatThrownBy { delivery.close() }
      .isSameAs(firstFailure)
      .hasSuppressedException(secondFailure)
    delivery.close()

    verify(firstWorker).close()
    verify(secondWorker).close()
    verify(thirdWorker).close()
    verifyNoMoreInteractions(firstWorker, secondWorker, thirdWorker)
  }

  @Test
  fun `should report a shared cleanup failure without suppressing itself`() {
    val firstWorker = mock<JobWorker>()
    val secondWorker = mock<JobWorker>()
    val failure = IllegalStateException("shared failure")
    doThrow(failure).whenever(firstWorker).close()
    doThrow(failure).whenever(secondWorker).close()
    val delivery = deliveryWithWorkers(mock(), firstWorker, secondWorker)
    delivery.subscribe()
    delivery.subscribe()

    assertThatThrownBy { delivery.close() }.isSameAs(failure)

    assertThat(failure.suppressed).isEmpty()
    verify(firstWorker).close()
    verify(secondWorker).close()
  }

  @Test
  fun `should close new workers after subscribing again following cleanup`() {
    val firstWorker = mock<JobWorker>()
    val secondWorker = mock<JobWorker>()
    val delivery = deliveryWithWorkers(mock(), firstWorker, secondWorker)
    delivery.subscribe()
    delivery.close()

    delivery.subscribe()
    delivery.close()

    verify(firstWorker).close()
    verify(secondWorker).close()
    verifyNoMoreInteractions(firstWorker, secondWorker)
  }

  @Test
  fun `should drain admitted callbacks before returning from close`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workerClosed = CountDownLatch(1)
    val fixture = CallbackFixture(action = TaskHandler { _, _ ->
      entered.countDown()
      check(release.await(30, TimeUnit.SECONDS)) { "handler was not released" }
    })
    doAnswer { workerClosed.countDown(); null }.whenever(fixture.worker).close()
    val callbacks = Executors.newSingleThreadExecutor()
    val closer = Executors.newSingleThreadExecutor()
    val job = activatedJob()
    val jobClient = mock<JobClient>()
    try {
      fixture.delivery.subscribe()
      val callback = callbacks.submit(Runnable { fixture.handlers.firstValue.handle(jobClient, job) })
      assertThat(entered.await(5, TimeUnit.SECONDS)).`as`("handler entered").isTrue()
      val closing = closer.submit(Runnable { fixture.delivery.close() })
      assertThat(workerClosed.await(5, TimeUnit.SECONDS)).`as`("worker acquisition stopped").isTrue()
      assertThatThrownBy { closing.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      closing.get(5, TimeUnit.SECONDS)
      verify(fixture.client).newWorker()
      verifyNoMoreInteractions(fixture.client)
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      closer.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
      closer.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `should reject callbacks queued before cleanup without touching application or repository`() {
    val actions = AtomicInteger()
    val terminations = AtomicInteger()
    val fixture = CallbackFixture(
      action = TaskHandler { _, _ -> actions.incrementAndGet() },
      termination = TaskTerminationHandler { terminations.incrementAndGet() }
    )
    val queueEntered = CountDownLatch(1)
    val releaseQueue = CountDownLatch(1)
    val callbacks = Executors.newSingleThreadExecutor()
    val job = activatedJob()
    val jobClient = mock<JobClient>()
    try {
      fixture.delivery.subscribe()
      callbacks.submit(Runnable {
        queueEntered.countDown()
        check(releaseQueue.await(30, TimeUnit.SECONDS)) { "callback queue was not released" }
      })
      assertThat(queueEntered.await(5, TimeUnit.SECONDS)).isTrue()
      val callback = callbacks.submit(Runnable { fixture.handlers.firstValue.handle(jobClient, job) })
      fixture.delivery.close()
      releaseQueue.countDown()
      callback.get(5, TimeUnit.SECONDS)

      assertThat(actions.get()).isZero()
      assertThat(terminations.get()).isZero()
      assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isNull()
      verify(fixture.client).newWorker()
      verifyNoMoreInteractions(fixture.client)
    } finally {
      releaseQueue.countDown()
      callbacks.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `should not revive old callbacks when subscribing again after cleanup`() {
    val actions = AtomicInteger()
    val fixture = CallbackFixture(action = TaskHandler { _, _ -> actions.incrementAndGet() })
    fixture.delivery.subscribe()
    val oldHandler = fixture.handlers.firstValue
    fixture.delivery.close()
    fixture.delivery.subscribe()
    try {
      oldHandler.handle(mock(), activatedJob())
      assertThat(actions.get()).`as`("closed registration remains rejected").isZero()
      assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isNull()
      fixture.handlers.allValues.last().handle(mock(), activatedJob())
      assertThat(actions.get()).isEqualTo(1)
      assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isNotNull()
    } finally {
      fixture.delivery.close()
    }
  }

  @Test
  fun `should disable every registration before closing the first worker`() {
    val actions = AtomicInteger()
    val fixture = CallbackFixture(action = TaskHandler { _, _ -> actions.incrementAndGet() })
    fixture.delivery.subscribe()
    fixture.delivery.subscribe()
    val job = activatedJob()
    val client = mock<JobClient>()
    doAnswer { fixture.handlers.allValues[1].handle(client, job); null }.whenever(fixture.worker).close()

    fixture.delivery.close()

    assertThat(actions.get()).isZero()
    assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isNull()
    verify(fixture.workers[1]).close()
    verify(fixture.client, times(2)).newWorker()
    verifyNoMoreInteractions(fixture.client)
  }

  @Test
  fun `should continue draining callbacks after a worker close failure`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val failure = IllegalStateException("worker close failed")
    val fixture = CallbackFixture(action = TaskHandler { _, _ ->
      entered.countDown()
      check(release.await(30, TimeUnit.SECONDS))
    })
    doAnswer { closed.countDown(); throw failure }.whenever(fixture.worker).close()
    val callbacks = Executors.newSingleThreadExecutor()
    val closer = Executors.newSingleThreadExecutor()
    val job = activatedJob()
    val client = mock<JobClient>()
    try {
      fixture.delivery.subscribe()
      val callback = callbacks.submit(Runnable { fixture.handlers.firstValue.handle(client, job) })
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
      val closing = closer.submit(Runnable { fixture.delivery.close() })
      assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
      assertThatThrownBy { closing.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
      assertThatThrownBy { fixture.delivery.close() }.hasMessageContaining("already in progress")
      assertThatThrownBy { fixture.delivery.subscribe() }.hasMessageContaining("in progress")
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      assertThatThrownBy { closing.get(5, TimeUnit.SECONDS) }.hasCause(failure)
      fixture.delivery.close()
      verify(fixture.worker).close()
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      closer.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
      closer.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `should bound cleanup and retain timed out callbacks for later cleanup`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val fixture = CallbackFixture(
      action = TaskHandler { _, _ -> entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) },
      shutdownTimeout = Duration.ofMillis(30)
    )
    val callbacks = Executors.newSingleThreadExecutor()
    val closer = Executors.newSingleThreadExecutor()
    val job = activatedJob()
    val client = mock<JobClient>()
    try {
      fixture.delivery.subscribe()
      fixture.delivery.subscribe()
      val callback = callbacks.submit(Runnable { fixture.handlers.firstValue.handle(client, job) })
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
      repeat(2) {
        val closing = closer.submit(Runnable { fixture.delivery.close() })
        assertThatThrownBy { closing.get(5, TimeUnit.SECONDS) }
          .hasCauseInstanceOf(IllegalStateException::class.java)
          .hasStackTraceContaining("did not drain")
      }
      fixture.workers.forEach { verify(it).close() }
      assertThatThrownBy { fixture.delivery.subscribe() }.hasMessageContaining("incomplete")
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      fixture.delivery.close()
      fixture.delivery.subscribe()
      fixture.delivery.close()
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      closer.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
      closer.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `should restore interruption and stop every worker before reporting incomplete drain`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val fixture = CallbackFixture(action = TaskHandler { _, _ ->
      entered.countDown()
      check(release.await(30, TimeUnit.SECONDS))
    })
    val callbacks = Executors.newSingleThreadExecutor()
    val job = activatedJob()
    val client = mock<JobClient>()
    try {
      fixture.delivery.subscribe()
      fixture.delivery.subscribe()
      val callback = callbacks.submit(Runnable { fixture.handlers.firstValue.handle(client, job) })
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
      Thread.currentThread().interrupt()
      try {
        assertThatThrownBy { fixture.delivery.close() }.isInstanceOf(InterruptedException::class.java)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
        fixture.workers.forEach { verify(it).close() }
      } finally {
        Thread.interrupted()
      }
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      fixture.delivery.close()
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `should release callback accounting after mapping failure and fatal handler errors`() {
    val fatal = AssertionError("fatal handler")
    val fixture = CallbackFixture(action = TaskHandler { _, _ -> throw fatal })
    val mappingFailure = IllegalStateException("variables unavailable")
    val job = activatedJob()
    whenever(job.variablesAsMap).thenThrow(mappingFailure)
    fixture.delivery.subscribe()
    assertThatThrownBy { fixture.handlers.firstValue.handle(mock(), job) }.isSameAs(mappingFailure)
    assertThatThrownBy { fixture.handlers.firstValue.handle(mock(), activatedJob()) }.isSameAs(fatal)
    fixture.delivery.close()
  }

  @Test
  fun `should drain failure reporting and termination and release accounting when both fail`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workerClosed = CountDownLatch(1)
    val reportFailure = IllegalStateException("failure command failed")
    val terminationFailure = AssertionError("termination failed")
    val fixture = CallbackFixture(
      action = TaskHandler { _, _ -> error("handler failed") },
      termination = TaskTerminationHandler { throw terminationFailure }
    )
    doAnswer { workerClosed.countDown(); null }.whenever(fixture.worker).close()
    whenever(fixture.client.newFailCommand(JOB_KEY)).thenAnswer {
      entered.countDown()
      check(release.await(30, TimeUnit.SECONDS))
      throw reportFailure
    }
    val callbacks = Executors.newSingleThreadExecutor()
    val closer = Executors.newSingleThreadExecutor()
    val job = activatedJob()
    val client = mock<JobClient>()
    try {
      fixture.delivery.subscribe()
      val callback = callbacks.submit(Runnable { fixture.handlers.firstValue.handle(client, job) })
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
      val closing = closer.submit(Runnable { fixture.delivery.close() })
      assertThat(workerClosed.await(5, TimeUnit.SECONDS)).isTrue()
      assertThatThrownBy { closing.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
      release.countDown()
      assertThatThrownBy { callback.get(5, TimeUnit.SECONDS) }.hasCause(terminationFailure)
      closing.get(5, TimeUnit.SECONDS)
      assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isNull()
      fixture.delivery.close()
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      closer.shutdownNow()
      callbacks.awaitTermination(5, TimeUnit.SECONDS)
      closer.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `should reject self drain while quiescing and allow cleanup after callback returns`() {
    lateinit var fixture: CallbackFixture
    fixture = CallbackFixture(action = TaskHandler { _, _ ->
      assertThatThrownBy { fixture.delivery.close() }.hasMessageContaining("Cannot drain")
    })
    fixture.delivery.subscribe()
    fixture.handlers.firstValue.handle(mock(), activatedJob())
    fixture.delivery.close()
    verify(fixture.worker).close()
  }

  @Test
  fun `should cancel a whole pending batch during synchronous opening callback and close late attachment`() {
    lateinit var fixture: CallbackFixture
    fixture = CallbackFixture(
      action = TaskHandler { _, _ ->
        assertThatThrownBy { fixture.delivery.close() }.hasMessageContaining("Cannot drain")
      },
      subscriptions = 2
    )
    val job = activatedJob()
    val client = mock<JobClient>()
    whenever(fixture.builder3.open()).thenAnswer {
      fixture.handlers.firstValue.handle(client, job)
      fixture.worker
    }
    fixture.delivery.subscribe()
    fixture.delivery.close()
    verify(fixture.client).newWorker()
    verify(fixture.worker).close()
    verify(fixture.workers[1], never()).close()
  }

  @Test
  fun `should await a cancelled opening and close its late attached worker exactly once`() {
    val opening = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val fixture = CallbackFixture(action = TaskHandler { _, _ -> error("cancelled callback") }, subscriptions = 3)
    val opens = AtomicInteger()
    doAnswer { closed.countDown(); null }.whenever(fixture.worker).close()
    whenever(fixture.builder3.open()).thenAnswer {
      if (opens.incrementAndGet() == 1) fixture.worker
      else {
        opening.countDown()
        check(release.await(30, TimeUnit.SECONDS))
        fixture.workers[1]
      }
    }
    val subscriber = Executors.newSingleThreadExecutor()
    val closer = Executors.newSingleThreadExecutor()
    try {
      val subscribing = subscriber.submit(Runnable { fixture.delivery.subscribe() })
      assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue()
      val closing = closer.submit(Runnable { fixture.delivery.close() })
      assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
      assertThatThrownBy { closing.get(200, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
      // The opener is still blocked; cleanup cannot claim that its worker is already closed.
      release.countDown()
      subscribing.get(5, TimeUnit.SECONDS)
      closing.get(5, TimeUnit.SECONDS)
      fixture.handlers.firstValue.handle(mock(), activatedJob())
      verify(fixture.worker).close()
      verify(fixture.workers[1]).close()
      verify(fixture.client, times(2)).newWorker()
      verifyNoMoreInteractions(fixture.client)
    } finally {
      release.countDown()
      subscriber.shutdownNow()
      closer.shutdownNow()
      subscriber.awaitTermination(5, TimeUnit.SECONDS)
      closer.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `should retain a timed out pending opening and reject its queued callbacks until late attachment closes`() {
    val opening = CountDownLatch(1)
    val release = CountDownLatch(1)
    val actions = AtomicInteger()
    val opens = AtomicInteger()
    val fixture = CallbackFixture(
      action = TaskHandler { _, _ -> actions.incrementAndGet() },
      shutdownTimeout = Duration.ofMillis(30)
    )
    whenever(fixture.builder3.open()).thenAnswer {
      if (opens.incrementAndGet() == 1) {
        opening.countDown()
        check(release.await(30, TimeUnit.SECONDS)) { "pending opening was not released" }
        fixture.worker
      } else fixture.workers[1]
    }
    val subscriber = Executors.newSingleThreadExecutor()
    val closer = Executors.newSingleThreadExecutor()
    val job = activatedJob()
    val client = mock<JobClient>()
    try {
      val subscribing = subscriber.submit(Runnable { fixture.delivery.subscribe() })
      assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue()
      val oldHandler = fixture.handlers.firstValue
      val queued = subscriber.submit(Runnable { oldHandler.handle(client, job) })
      val closing = closer.submit(Runnable { fixture.delivery.close() })
      assertThatThrownBy { closing.get(5, TimeUnit.SECONDS) }
        .hasCauseInstanceOf(IllegalStateException::class.java)
        .hasStackTraceContaining("did not drain")
      assertThatThrownBy { fixture.delivery.subscribe() }.hasMessageContaining("incomplete")
      oldHandler.handle(client, job)
      assertThat(actions.get()).isZero()
      assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isNull()
      verify(fixture.worker, never()).close()
      release.countDown()
      subscribing.get(5, TimeUnit.SECONDS)
      queued.get(5, TimeUnit.SECONDS)
      assertThat(actions.get()).isZero()
      verify(fixture.worker).close()
      fixture.delivery.close()
      verify(fixture.client).newWorker()
      verifyNoMoreInteractions(fixture.client)

      fixture.delivery.subscribe()
      fixture.handlers.allValues.last().handle(client, job)
      assertThat(actions.get()).isEqualTo(1)
      fixture.delivery.close()
      verify(fixture.worker).close()
      verify(fixture.workers[1]).close()
    } finally {
      release.countDown()
      subscriber.shutdownNow()
      closer.shutdownNow()
      subscriber.awaitTermination(5, TimeUnit.SECONDS)
      closer.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `should not open service workers for empty repositories or user task subscriptions`() {
    val repository = InMemSubscriptionRepository()
    val client = mock<CamundaClient>()
    val service = SubscribingServiceTaskDelivery(client, repository, "test-worker", 10, 60)
    service.subscribe()
    repository.createTaskSubscription(TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = TOPIC,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    ))

    service.subscribe()
    service.close()

    verifyNoMoreInteractions(client)
  }

  @Test
  fun `should close opened workers and cancel pending registrations after a partial opening failure`() {
    val failure = IllegalStateException("open failed")
    val fixture = CallbackFixture(action = TaskHandler { _, _ -> error("cancelled callback") }, subscriptions = 3)
    whenever(fixture.builder3.open()).thenReturn(fixture.worker).thenThrow(failure)
    val cleanupFailure = IllegalStateException("close failed")
    doThrow(cleanupFailure).whenever(fixture.worker).close()

    assertThatThrownBy { fixture.delivery.subscribe() }.isSameAs(failure).hasSuppressedException(cleanupFailure)

    fixture.handlers.allValues.forEach { it.handle(mock(), activatedJob()) }
    fixture.delivery.close()
    verify(fixture.worker).close()
    verify(fixture.client, times(2)).newWorker()
    verifyNoMoreInteractions(fixture.client)
  }

  @Test
  fun `should reject self wait from open without an admitted callback and report late close failure`() {
    val fixture = CallbackFixture(action = TaskHandler { _, _ -> error("cancelled callback") })
    val failure = IllegalStateException("late worker close failed")
    doThrow(failure).whenever(fixture.worker).close()
    whenever(fixture.builder3.open()).thenAnswer {
      assertThatThrownBy { fixture.delivery.close() }.hasMessageContaining("worker-opening thread")
      fixture.worker
    }

    assertThatThrownBy { fixture.delivery.subscribe() }.isSameAs(failure)

    fixture.handlers.firstValue.handle(mock(), activatedJob())
    fixture.delivery.close()
    verify(fixture.worker).close()
  }

  @Test
  fun `should admit synchronous callbacks before worker attachment`() {
    val actions = AtomicInteger()
    val fixture = CallbackFixture(action = TaskHandler { _, _ -> actions.incrementAndGet() })
    val job = activatedJob()
    val client = mock<JobClient>()
    whenever(fixture.builder3.open()).thenAnswer {
      fixture.handlers.firstValue.handle(client, job)
      fixture.worker
    }

    fixture.delivery.subscribe()
    fixture.delivery.close()

    assertThat(actions.get()).isEqualTo(1)
    verify(fixture.worker).close()
  }

  @Test
  fun `should require a positive representable callback drain timeout`() {
    listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { timeout ->
      assertThatThrownBy { CallbackFixture(TaskHandler { _, _ -> }, shutdownTimeout = timeout) }
        .isInstanceOf(IllegalArgumentException::class.java)
    }
    assertThatThrownBy { CallbackFixture(TaskHandler { _, _ -> }, shutdownTimeout = Duration.ofSeconds(Long.MAX_VALUE)) }
      .isInstanceOf(ArithmeticException::class.java)
  }

  private class CallbackFixture(
    action: TaskHandler,
    termination: TaskTerminationHandler = TaskTerminationHandler { _ -> },
    shutdownTimeout: Duration = Duration.ofSeconds(30),
    subscriptions: Int = 1
  ) {
    val repository = InMemSubscriptionRepository().apply {
      repeat(subscriptions) { index ->
        createTaskSubscription(TaskSubscriptionHandle(
          taskType = TaskType.EXTERNAL,
          taskDescriptionKey = if (index == 0) TOPIC else "$TOPIC-$index",
          restrictions = emptyMap(),
          payloadDescription = null,
          action = action,
          termination = termination
        ))
      }
    }
    val client = mock<CamundaClient>()
    val workers = listOf(mock<JobWorker>(), mock<JobWorker>())
    val worker = workers.first()
    val handlers = argumentCaptor<JobHandler>()
    val builder3 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>()
    val delivery: SubscribingServiceTaskDelivery

    init {
      val builder = mock<JobWorkerBuilderStep1>()
      val builder2 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
      whenever(client.newWorker()).thenReturn(builder)
      whenever(builder.jobType(any())).thenReturn(builder2)
      whenever(builder2.handler(handlers.capture())).thenReturn(builder3)
      whenever(builder3.name(any())).thenReturn(builder3)
      whenever(builder3.timeout(any<Long>())).thenReturn(builder3)
      whenever(builder3.open()).thenReturn(worker, workers[1])
      delivery = SubscribingServiceTaskDelivery(client, repository, "test-worker", 10, 60, shutdownTimeout)
    }
  }

  private fun deliveryWithWorkers(client: CamundaClient, vararg workers: JobWorker): SubscribingServiceTaskDelivery {
    val repository = InMemSubscriptionRepository().apply {
      createTaskSubscription(TaskSubscriptionHandle(
        taskType = TaskType.EXTERNAL,
        taskDescriptionKey = TOPIC,
        restrictions = emptyMap(),
        payloadDescription = null,
        action = { _, _ -> },
        termination = { _ -> }
      ))
    }
    val builder = mock<JobWorkerBuilderStep1>()
    val builder2 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
    val builder3 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>()
    val workerIterator = workers.iterator()
    whenever(client.newWorker()).thenReturn(builder)
    whenever(builder.jobType(TOPIC)).thenReturn(builder2)
    whenever(builder2.handler(any())).thenReturn(builder3)
    whenever(builder3.name(any())).thenReturn(builder3)
    whenever(builder3.timeout(any<Long>())).thenReturn(builder3)
    whenever(builder3.open()).thenAnswer { workerIterator.next() }
    return SubscribingServiceTaskDelivery(client, repository, "test-worker", 10, 60)
  }

  private fun activatedJob(): ActivatedJob =
    mock {
      whenever(it.key).thenReturn(JOB_KEY)
      whenever(it.retries).thenReturn(RETRIES)
      whenever(it.type).thenReturn(TOPIC)
      whenever(it.elementId).thenReturn(ELEMENT_ID)
      whenever(it.tenantId).thenReturn(TENANT_ID)
      whenever(it.processInstanceKey).thenReturn(PROCESS_INSTANCE_KEY)
      whenever(it.processDefinitionKey).thenReturn(PROCESS_DEFINITION_KEY)
      whenever(it.variablesAsMap).thenReturn(emptyMap())
      whenever(it.customHeaders).thenReturn(emptyMap())
    }
}
