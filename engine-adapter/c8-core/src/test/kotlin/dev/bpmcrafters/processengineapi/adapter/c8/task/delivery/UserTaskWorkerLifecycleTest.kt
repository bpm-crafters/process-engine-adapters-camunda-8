package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import io.camunda.client.api.worker.JobWorker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class UserTaskWorkerLifecycleTest {
  @Test
  fun `rejects nonpositive and unrepresentable shutdown bounds`() {
    listOf(Duration.ZERO, Duration.ofNanos(-1)).forEach { duration ->
      assertThatThrownBy { UserTaskWorkerLifecycle(duration) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    assertThatThrownBy { UserTaskWorkerLifecycle(Duration.ofSeconds(Long.MAX_VALUE)) }
      .isInstanceOf(ArithmeticException::class.java)
  }

  @Test
  fun `disabled registration retains its owned worker until cancellation claims it`() {
    val lifecycle = UserTaskWorkerLifecycle(Duration.ofSeconds(2))
    val registration = synchronized(lifecycle.monitor) { lifecycle.register() }
    val worker = mock<JobWorker>()
    lifecycle.open(registration) { worker }

    // unsubscribe() disables its external registry entry before cancel() claims the worker.
    synchronized(lifecycle.monitor) { registration.accepting = false }
    // Another registration settling can prune during that gap, without any thread timing dependency.
    val other = synchronized(lifecycle.monitor) { lifecycle.register() }
    lifecycle.settle(other)

    try {
      lifecycle.close()
      verify(worker).close()
      lifecycle.cancel(listOf(registration))
      verify(worker).close()
    } finally {
      lifecycle.cancel(listOf(registration))
      lifecycle.close()
    }
  }

  @Test
  fun `worker cancellation cannot self wait through a reentrant close`() {
    val lifecycle = UserTaskWorkerLifecycle(Duration.ofSeconds(2))
    val registration = synchronized(lifecycle.monitor) { lifecycle.register() }
    val worker = mock<JobWorker>()
    doAnswer {
      assertThatThrownBy { lifecycle.close() }.hasMessageContaining("worker-closing thread")
      null
    }.whenever(worker).close()
    lifecycle.open(registration) { worker }

    lifecycle.cancel(listOf(registration))
    lifecycle.close()

    verify(worker).close()
  }

  @Test
  fun `worker closing failures do not skip other workers or callback drain`() {
    val lifecycle = UserTaskWorkerLifecycle(Duration.ofSeconds(2))
    val first = mock<JobWorker>()
    val second = mock<JobWorker>()
    val firstFailure = IllegalStateException("first close failed")
    val secondFailure = AssertionError("second close failed")
    val workersClosed = CountDownLatch(1)
    doThrow(firstFailure).whenever(first).close()
    doAnswer { workersClosed.countDown(); throw secondFailure }.whenever(second).close()
    val registrations = synchronized(lifecycle.monitor) {
      lifecycle.checkSubscriptionAllowed()
      listOf(lifecycle.register(), lifecycle.register())
    }
    lifecycle.open(registrations[0]) { first }
    lifecycle.open(registrations[1]) { second }
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val callbacks = Executors.newSingleThreadExecutor()
    val stopper = Executors.newSingleThreadExecutor()
    try {
      val callback = callbacks.submit {
        lifecycle.deliver(registrations[0]) {
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
        }
      }
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
      val stopping = stopper.submit { lifecycle.close() }
      assertThat(workersClosed.await(5, TimeUnit.SECONDS)).isTrue()
      assertThatThrownBy { stopping.get(100, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
      release.countDown()
      callback.get(5, TimeUnit.SECONDS)
      assertThatThrownBy { stopping.get(5, TimeUnit.SECONDS) }.hasCause(firstFailure)
      assertThat(firstFailure.suppressed).containsExactly(secondFailure)
      verify(first).close()
      verify(second).close()
      lifecycle.close()
      verify(first).close()
      verify(second).close()
    } finally {
      release.countDown()
      callbacks.shutdownNow()
      stopper.shutdownNow()
      assertThat(callbacks.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
      assertThat(stopper.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
    }
  }
}
