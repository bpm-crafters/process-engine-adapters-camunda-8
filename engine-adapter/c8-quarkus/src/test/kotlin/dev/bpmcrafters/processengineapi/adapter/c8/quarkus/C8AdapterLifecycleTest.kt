package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.ServiceTaskDeliveryStrategy
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.UserTaskDeliveryStrategy
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.RefreshableDelivery
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class C8AdapterLifecycleTest {

  private val bindings = mockk<C8AdapterBindings>(relaxed = true).also {
    every { it.hasServiceTaskDelivery() } returns true
    every { it.hasUserTaskDelivery() } returns true
  }
  private val bindingsInstance = mockk<Instance<C8AdapterBindings>>().also {
    every { it.get() } returns bindings
  }

  private fun refreshableHandle(delivery: RefreshableDelivery?): Instance<RefreshableDelivery> = mockk {
    every { isResolvable } returns (delivery != null)
    if (delivery != null) {
      every { get() } returns delivery
    }
  }

  @Test
  fun `skips lifecycle bindings for disabled adapter`() {
    val lifecycle = C8AdapterLifecycle(testProperties(adapterEnabled = false), bindingsInstance, refreshableHandle(null))

    lifecycle.onStart(StartupEvent())
    lifecycle.onStop(ShutdownEvent())

    verify { bindingsInstance wasNot Called }
    verify { bindings wasNot Called }
  }

  @Test
  fun `starts bindings and refreshes delivery at fixed rate`() {
    val delivery = mockk<RefreshableDelivery>(relaxed = true)
    val lifecycle = C8AdapterLifecycle(
      testProperties(fixedRateInSeconds = 1),
      bindingsInstance,
      refreshableHandle(delivery)
    )

    lifecycle.onStart(StartupEvent())

    verify(timeout = 3000) { bindings.startServiceTasks() }
    verify(timeout = 3000) { bindings.startUserTasks() }
    verify(timeout = 5000, atLeast = 2) { delivery.refresh() }

    lifecycle.onStop(ShutdownEvent())

    verify { bindings.close() }
  }

  @Test
  fun `starts bindings without scheduling refresh for listener strategy`() {
    val lifecycle = C8AdapterLifecycle(
      testProperties(userTaskDeliveryStrategy = UserTaskDeliveryStrategy.LISTENER),
      bindingsInstance,
      refreshableHandle(null)
    )

    lifecycle.onStart(StartupEvent())

    verify(timeout = 3000) { bindings.startServiceTasks() }
    verify(timeout = 3000) { bindings.startUserTasks() }

    lifecycle.onStop(ShutdownEvent())

    verify { bindings.close() }
  }

  @Test
  fun `subscribes user tasks even if service task subscription fails`() {
    every { bindings.startServiceTasks() } throws IllegalStateException("boom")
    val lifecycle = C8AdapterLifecycle(testProperties(), bindingsInstance, refreshableHandle(null))

    lifecycle.onStart(StartupEvent())

    verify(timeout = 3000) { bindings.startUserTasks() }

    lifecycle.onStop(ShutdownEvent())
  }

  @Test
  fun `fails startup when required service task configuration is missing`() {
    assertThatThrownBy {
      C8AdapterLifecycle(testProperties(serviceTaskDeliveryStrategy = null), bindingsInstance, refreshableHandle(null))
        .onStart(StartupEvent())
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("service-tasks.delivery-strategy")

    assertThatThrownBy {
      C8AdapterLifecycle(testProperties(serviceTaskWorkerId = null), bindingsInstance, refreshableHandle(null))
        .onStart(StartupEvent())
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("service-tasks.worker-id")

    assertThatThrownBy {
      C8AdapterLifecycle(testProperties(userTaskDeliveryStrategy = null), bindingsInstance, refreshableHandle(null))
        .onStart(StartupEvent())
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("user-tasks.delivery-strategy")

    verify { bindings wasNot Called }
  }

  @Test
  fun `does not require a worker id for custom service task delivery`() {
    val lifecycle = C8AdapterLifecycle(
      testProperties(
        serviceTaskDeliveryStrategy = ServiceTaskDeliveryStrategy.CUSTOM,
        serviceTaskWorkerId = null,
        userTaskDeliveryStrategy = UserTaskDeliveryStrategy.SCHEDULED
      ),
      bindingsInstance,
      refreshableHandle(null)
    )

    assertThatCode { lifecycle.onStart(StartupEvent()) }.doesNotThrowAnyException()

    lifecycle.onStop(ShutdownEvent())
  }
}
