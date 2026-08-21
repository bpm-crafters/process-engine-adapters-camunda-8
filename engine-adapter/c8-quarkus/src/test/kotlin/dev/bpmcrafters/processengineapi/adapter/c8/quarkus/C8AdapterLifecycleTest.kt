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

  private val bindings = mockk<C8AdapterBindings>(relaxed = true)
  private val bindingsInstance = mockk<Instance<C8AdapterBindings>>().also {
    every { it.get() } returns bindings
  }

  @Test
  fun `skips lifecycle bindings for disabled adapter`() {
    val lifecycle = C8AdapterLifecycle(testProperties(adapterEnabled = false), bindingsInstance)

    lifecycle.onStart(StartupEvent())
    lifecycle.onStop(ShutdownEvent())

    verify { bindingsInstance wasNot Called }
    verify { bindings wasNot Called }
  }

  @Test
  fun `starts bindings and refreshes delivery at fixed rate`() {
    val delivery = mockk<RefreshableDelivery>(relaxed = true)
    every { bindings.refreshableUserTaskDelivery } returns delivery
    val lifecycle = C8AdapterLifecycle(testProperties(fixedRateInSeconds = 1), bindingsInstance)

    lifecycle.onStart(StartupEvent())

    verify(timeout = 3000) { bindings.startServiceTasks() }
    verify(timeout = 3000) { bindings.startUserTasks() }
    verify(timeout = 5000, atLeast = 2) { delivery.refresh() }

    lifecycle.onStop(ShutdownEvent())

    verify { bindings.close() }
  }

  @Test
  fun `starts bindings without scheduling refresh for listener strategy`() {
    every { bindings.refreshableUserTaskDelivery } returns null
    val lifecycle = C8AdapterLifecycle(
      testProperties(userTaskDeliveryStrategy = UserTaskDeliveryStrategy.LISTENER),
      bindingsInstance
    )

    lifecycle.onStart(StartupEvent())

    verify(timeout = 3000) { bindings.startServiceTasks() }
    verify(timeout = 3000) { bindings.startUserTasks() }

    lifecycle.onStop(ShutdownEvent())

    verify { bindings.close() }
  }

  @Test
  fun `subscribes user tasks even if service task subscription fails`() {
    every { bindings.refreshableUserTaskDelivery } returns null
    every { bindings.startServiceTasks() } throws IllegalStateException("boom")
    val lifecycle = C8AdapterLifecycle(testProperties(), bindingsInstance)

    lifecycle.onStart(StartupEvent())

    verify(timeout = 3000) { bindings.startUserTasks() }

    lifecycle.onStop(ShutdownEvent())
  }

  @Test
  fun `fails startup when required service task configuration is missing`() {
    assertThatThrownBy {
      C8AdapterLifecycle(testProperties(serviceTaskDeliveryStrategy = null), bindingsInstance)
        .onStart(StartupEvent())
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("service-tasks.delivery-strategy")

    assertThatThrownBy {
      C8AdapterLifecycle(testProperties(serviceTaskWorkerId = null), bindingsInstance)
        .onStart(StartupEvent())
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("service-tasks.worker-id")

    assertThatThrownBy {
      C8AdapterLifecycle(testProperties(userTaskDeliveryStrategy = null), bindingsInstance)
        .onStart(StartupEvent())
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("user-tasks.delivery-strategy")

    verify { bindings wasNot Called }
  }

  @Test
  fun `does not require a worker id for custom service task delivery`() {
    every { bindings.refreshableUserTaskDelivery } returns null
    val lifecycle = C8AdapterLifecycle(
      testProperties(
        serviceTaskDeliveryStrategy = ServiceTaskDeliveryStrategy.CUSTOM,
        serviceTaskWorkerId = null,
        userTaskDeliveryStrategy = UserTaskDeliveryStrategy.SCHEDULED
      ),
      bindingsInstance
    )

    assertThatCode { lifecycle.onStart(StartupEvent()) }.doesNotThrowAnyException()

    lifecycle.onStop(ShutdownEvent())
  }
}
