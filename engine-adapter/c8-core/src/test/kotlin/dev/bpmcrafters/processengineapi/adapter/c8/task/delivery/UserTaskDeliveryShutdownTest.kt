package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.CompleteJobCommandStep1
import io.camunda.client.api.command.FailJobCommandStep1
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.response.CompleteJobResponse
import io.camunda.client.api.response.FailJobResponse
import io.camunda.client.api.response.UserTaskProperties
import io.camunda.client.api.search.enums.JobKind
import io.camunda.client.api.search.enums.ListenerEventType
import io.camunda.client.api.worker.JobHandler
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class UserTaskDeliveryShutdownTest {
  @Test
  fun `close drains an unsubscribed handler returning its job and ignores subsequent activations`() {
    val fixture = Fixture(listener = false, timeout = Duration.ofMillis(40))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    fixture.onFail = {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }
    fixture.action = { error("unsubscribed handler must not deliver to the application") }
    val callbacks = Executors.newSingleThreadExecutor()
    try {
      fixture.subscribe()
      fixture.unsubscribe()
      val oldHandler = fixture.handlers.firstValue
      val callback = callbacks.submit { oldHandler.handle(mock(), fixture.job) }
      assertThat(entered.await(5, TimeUnit.SECONDS)).`as`("late job return is joining the SDK future").isTrue()
      assertThatThrownBy { fixture.close() }.hasMessageContaining("did not drain")
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      fixture.close()
      oldHandler.handle(mock(), fixture.job)
      verify(fixture.client, times(1)).newFailCommand(1234L)
      fixture.assertLateCallbackIgnored()
      verify(fixture.worker).close()
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      assertThat(callbacks.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
      fixture.close()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `timeout retains a callback even after it unsubscribes and retry drains it`(listener: Boolean) {
    val fixture = Fixture(listener, Duration.ofMillis(40))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    fixture.action = {
      fixture.unsubscribe()
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }
    val callbacks = Executors.newSingleThreadExecutor()
    try {
      fixture.subscribe()
      val callback = callbacks.submit { fixture.handle() }
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
      assertThatThrownBy { fixture.close() }.hasMessageContaining("did not drain")
      assertThatThrownBy { fixture.subscribe() }.hasMessageContaining("incomplete")
      fixture.assertLateCallbackIgnored()
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      fixture.close()
      fixture.assertLateCallbackIgnored()
      verify(fixture.worker).close()
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      assertThat(callbacks.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
      fixture.close()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `pending opening is drained and a late attached worker closes exactly once`(listener: Boolean) {
    val fixture = Fixture(listener, Duration.ofMillis(40))
    val opening = CountDownLatch(1)
    val release = CountDownLatch(1)
    fixture.onOpen = {
      opening.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }
    val opener = Executors.newSingleThreadExecutor()
    try {
      val subscribed = opener.submit { fixture.subscribe() }
      assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue()
      assertThatThrownBy { fixture.close() }.hasMessageContaining("did not drain")
      fixture.assertLateCallbackIgnored()
      release.countDown()
      subscribed.get(5, TimeUnit.SECONDS)
      fixture.close()
      verify(fixture.worker).close()
    } finally {
      release.countDown()
      opener.shutdownNow()
      assertThat(opener.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
      fixture.close()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `self drain fails promptly and Error releases accounting without reviving old handlers`(listener: Boolean) {
    val fixture = Fixture(listener)
    val fatal = AssertionError("application fatal error")
    fixture.action = {
      assertThatThrownBy { fixture.close() }.hasMessageContaining("callback or worker-opening")
      throw fatal
    }
    fixture.subscribe()
    val oldHandler = fixture.handlers.firstValue
    assertThatThrownBy { fixture.handle() }.isSameAs(fatal)
    fixture.close()
    fixture.assertLateCallbackIgnored()
    fixture.action = {}
    fixture.subscribe()
    clearInvocations(fixture.client, fixture.repository)
    oldHandler.handle(mock(), fixture.job)
    verifyNoInteractions(fixture.client, fixture.repository)
    fixture.handle()
    fixture.close()
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `self drain during opening fails without deadlocking or leaking the late worker`(listener: Boolean) {
    val fixture = Fixture(listener)
    fixture.onOpen = {
      assertThatThrownBy { fixture.close() }.hasMessageContaining("callback or worker-opening")
    }
    fixture.subscribe()
    fixture.close()
    fixture.assertLateCallbackIgnored()
    verify(fixture.worker).close()
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `failure command join remains inside the drain`(listener: Boolean) {
    val fixture = Fixture(listener)
    fixture.action = { error("application failure") }
    fixture.assertBlockedCallbackDrains { block -> fixture.onFail = block }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `termination callback remains inside the drain`(listener: Boolean) {
    val fixture = Fixture(listener)
    if (listener) {
      fixture.repository.activateSubscriptionForTask("5678", fixture.subscription)
      whenever(fixture.job.listenerEventType).thenReturn(ListenerEventType.CANCELING)
    } else {
      fixture.action = { error("application failure") }
    }
    fixture.assertBlockedCallbackDrains { block -> fixture.termination = block }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `interruption restores flag and retains callback for cleanup retry`(listener: Boolean) {
    val fixture = Fixture(listener)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    fixture.action = {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }
    val callbacks = Executors.newSingleThreadExecutor()
    try {
      fixture.subscribe()
      val callback = callbacks.submit { fixture.handle() }
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
      Thread.currentThread().interrupt()
      try {
        assertThatThrownBy { fixture.close() }.isInstanceOf(InterruptedException::class.java)
        assertThat(Thread.currentThread().isInterrupted).isTrue()
      } finally {
        Thread.interrupted()
      }
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      fixture.close()
      verify(fixture.worker).close()
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      assertThat(callbacks.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
      fixture.close()
    }
  }

  private class Fixture(val listener: Boolean, timeout: Duration = Duration.ofSeconds(2)) {
    val repository = org.mockito.kotlin.spy(InMemSubscriptionRepository())
    val client = mock<CamundaClient>()
    val worker = mock<JobWorker>()
    val workerClosed = CountDownLatch(1)
    val handlers = argumentCaptor<JobHandler>()
    var action: () -> Unit = {}
    var termination: () -> Unit = {}
    var onOpen: () -> Unit = {}
    var onFail: () -> Unit = {}
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> action() },
      termination = { _ -> termination() }
    )
    val listenerDelivery = ListenerUserTaskDelivery(client, repository, shutdownTimeout = timeout)
    val refreshingDelivery = SubscribingRefreshingZeebeJobUserTaskDelivery(client, repository, "worker", 60000, timeout)
    val job: ActivatedJob

    init {
      repository.createTaskSubscription(subscription)
      val first = mock<JobWorkerBuilderStep1>()
      val second = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
      val third = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>(defaultAnswer = RETURNS_SELF)
      whenever(client.newWorker()).thenReturn(first)
      whenever(first.jobType(any())).thenReturn(second)
      whenever(second.handler(handlers.capture())).thenReturn(third)
      whenever(third.open()).thenAnswer { onOpen(); worker }
      doAnswer { workerClosed.countDown(); null }.whenever(worker).close()
      val complete = mock<CompleteJobCommandStep1>()
      val completed = mock<CamundaFuture<CompleteJobResponse>>()
      whenever(client.newCompleteCommand(any<Long>())).thenReturn(complete)
      whenever(complete.send()).thenReturn(completed)
      val fail = mock<FailJobCommandStep1>()
      val fail2 = mock<FailJobCommandStep1.FailJobCommandStep2>(defaultAnswer = RETURNS_SELF)
      val failed = mock<CamundaFuture<FailJobResponse>>()
      whenever(client.newFailCommand(any<Long>())).thenReturn(fail)
      whenever(fail.retries(any())).thenReturn(fail2)
      whenever(fail2.send()).thenReturn(failed)
      whenever(failed.join()).thenAnswer { onFail(); null }
      val userTask = mock<UserTaskProperties> { whenever(it.userTaskKey).thenReturn(5678L) }
      job = mock {
        whenever(it.key).thenReturn(1234L)
        whenever(it.elementId).thenReturn("user-task")
        whenever(it.variablesAsMap).thenReturn(emptyMap())
        whenever(it.customHeaders).thenReturn(emptyMap())
        whenever(it.kind).thenReturn(JobKind.TASK_LISTENER)
        whenever(it.listenerEventType).thenReturn(ListenerEventType.CREATING)
        whenever(it.userTask).thenReturn(userTask)
      }
    }

    fun subscribe() = if (listener) listenerDelivery.subscribe() else refreshingDelivery.subscribe()
    fun close() = if (listener) listenerDelivery.close() else refreshingDelivery.close()
    fun unsubscribe() = if (listener) listenerDelivery.unsubscribe(subscription) else refreshingDelivery.unsubscribe(subscription)
    fun handle() = handlers.lastValue.handle(mock(), job)

    fun assertLateCallbackIgnored() {
      clearInvocations(client, repository)
      handle()
      verifyNoInteractions(client, repository)
    }

    fun assertBlockedCallbackDrains(install: (() -> Unit) -> Unit) {
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      install {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
      }
      val callbacks = Executors.newSingleThreadExecutor()
      val stopper = Executors.newSingleThreadExecutor()
      try {
        subscribe()
        val callback = callbacks.submit { handle() }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val stopping = stopper.submit { close() }
        assertThat(workerClosed.await(5, TimeUnit.SECONDS)).isTrue()
        assertThatThrownBy { stopping.get(100, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
        assertThatThrownBy { close() }.hasMessageContaining("already in progress")
        release.countDown()
        callback.get(5, TimeUnit.SECONDS)
        stopping.get(5, TimeUnit.SECONDS)
        assertLateCallbackIgnored()
        verify(worker).close()
      } finally {
        release.countDown()
        callbacks.shutdownNow()
        stopper.shutdownNow()
        assertThat(callbacks.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        assertThat(stopper.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        close()
      }
    }
  }
}
