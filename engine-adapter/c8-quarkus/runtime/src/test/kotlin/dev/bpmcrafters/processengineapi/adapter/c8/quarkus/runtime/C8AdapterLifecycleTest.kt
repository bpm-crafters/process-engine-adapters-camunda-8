package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    order.verify(fixture.refresh).unsubscribeAll()
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
    verify(fixture.refresh).unsubscribeAll()
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

  private class Fixture(serviceStrategy: String, userStrategy: String, settings: Map<String, String>) {
    val service = mock<SubscribingServiceTaskDelivery>()
    val pull = mock<PullUserTaskDelivery>()
    val refresh = mock<SubscribingRefreshingZeebeJobUserTaskDelivery>()
    val listener = mock<ListenerUserTaskDelivery>()
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
