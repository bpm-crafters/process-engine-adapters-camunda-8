package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.event.ApplicationStartedEvent

class UserTaskListenerDeliveryBindingTest {

  private val listenerUserTaskDelivery: ListenerUserTaskDelivery = mockk(relaxed = true)
  private val listenerPreloadUserTaskDelivery: PullUserTaskDelivery = mockk(relaxed = true)
  private val globalUserTaskListenerRegistrationHelper: GlobalUserTaskListenerRegistrationHelper = mockk(relaxed = true)
  private val event: ApplicationStartedEvent = mockk()

  @Test
  fun `should register preload and subscribe in order when preload is enabled`() {
    binding(preloadExistingTasks = true).scheduleUserTaskListenerSubscription(event)

    verifyOrder {
      globalUserTaskListenerRegistrationHelper.registerIfEnabled()
      listenerPreloadUserTaskDelivery.refresh()
      listenerUserTaskDelivery.subscribe()
    }
  }

  @Test
  fun `should skip preload when preload is disabled`() {
    binding(preloadExistingTasks = false).scheduleUserTaskListenerSubscription(event)

    verifyOrder {
      globalUserTaskListenerRegistrationHelper.registerIfEnabled()
      listenerUserTaskDelivery.subscribe()
    }
    verify(exactly = 0) { listenerPreloadUserTaskDelivery.refresh() }
  }

  @Test
  fun `should subscribe when preload throws`() {
    every { listenerPreloadUserTaskDelivery.refresh() } throws RuntimeException("boom")

    binding(preloadExistingTasks = true).scheduleUserTaskListenerSubscription(event)

    verifyOrder {
      globalUserTaskListenerRegistrationHelper.registerIfEnabled()
      listenerPreloadUserTaskDelivery.refresh()
      listenerUserTaskDelivery.subscribe()
    }
  }

  @Test
  fun `should not continue when global listener registration throws`() {
    every { globalUserTaskListenerRegistrationHelper.registerIfEnabled() } throws IllegalStateException("registration failed")

    assertThatThrownBy {
      binding(preloadExistingTasks = true).scheduleUserTaskListenerSubscription(event)
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessage("registration failed")

    verify(exactly = 0) { listenerPreloadUserTaskDelivery.refresh() }
    verify(exactly = 0) { listenerUserTaskDelivery.subscribe() }
  }

  private fun binding(preloadExistingTasks: Boolean): UserTaskListenerDeliveryBinding =
    UserTaskListenerDeliveryBinding(
      listenerUserTaskDelivery = listenerUserTaskDelivery,
      listenerPreloadUserTaskDelivery = listenerPreloadUserTaskDelivery,
      globalUserTaskListenerRegistrationHelper = globalUserTaskListenerRegistrationHelper,
      c8AdapterProperties = properties(preloadExistingTasks = preloadExistingTasks)
    )

  private fun properties(preloadExistingTasks: Boolean): C8AdapterProperties =
    C8AdapterProperties(
      enabled = true,
      serviceTasks = C8AdapterProperties.ServiceTasks(
        deliveryStrategy = C8AdapterProperties.ServiceTaskDeliveryStrategy.SUBSCRIPTION,
        workerId = "execute-action-external"
      ),
      userTasks = C8AdapterProperties.UserTasks(
        deliveryStrategy = C8AdapterProperties.UserTaskDeliveryStrategy.LISTENER,
        listener = C8AdapterProperties.UserTaskListener(
          preloadExistingTasks = preloadExistingTasks
        )
      )
    )
}
