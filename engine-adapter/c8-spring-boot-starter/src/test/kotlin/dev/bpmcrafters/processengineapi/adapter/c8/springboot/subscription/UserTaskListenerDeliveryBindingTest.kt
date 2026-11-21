package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationRunningEvent
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationStoppingEvent
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UserTaskListenerDeliveryBindingTest {

  private val listenerUserTaskDelivery: ListenerUserTaskDelivery = mock()
  private val listenerPreloadUserTaskDelivery: PullUserTaskDelivery = mock()
  private val globalUserTaskListenerRegistrationHelper: GlobalUserTaskListenerRegistrationHelper = mock()
  private val runningEvent: ProcessApplicationRunningEvent = mock()
  private val stoppingEvent: ProcessApplicationStoppingEvent = mock()

  @Test
  fun `should register preload and subscribe in order when preload is enabled`() {
    binding(preloadExistingTasks = true).start(runningEvent)

    inOrder(
      globalUserTaskListenerRegistrationHelper,
      listenerPreloadUserTaskDelivery,
      listenerUserTaskDelivery
    ).apply {
      verify(globalUserTaskListenerRegistrationHelper).registerIfEnabled()
      verify(listenerPreloadUserTaskDelivery).refresh()
      verify(listenerUserTaskDelivery).subscribe()
    }
  }

  @Test
  fun `should skip preload when preload is disabled`() {
    binding(preloadExistingTasks = false).start(runningEvent)

    inOrder(globalUserTaskListenerRegistrationHelper, listenerUserTaskDelivery).apply {
      verify(globalUserTaskListenerRegistrationHelper).registerIfEnabled()
      verify(listenerUserTaskDelivery).subscribe()
    }
    verify(listenerPreloadUserTaskDelivery, never()).refresh()
  }

  @Test
  fun `should subscribe when preload throws`() {
    whenever(listenerPreloadUserTaskDelivery.refresh()).thenThrow(RuntimeException("boom"))

    binding(preloadExistingTasks = true).start(runningEvent)

    inOrder(
      globalUserTaskListenerRegistrationHelper,
      listenerPreloadUserTaskDelivery,
      listenerUserTaskDelivery
    ).apply {
      verify(globalUserTaskListenerRegistrationHelper).registerIfEnabled()
      verify(listenerPreloadUserTaskDelivery).refresh()
      verify(listenerUserTaskDelivery).subscribe()
    }
  }

  @Test
  fun `should not continue when global listener registration throws`() {
    whenever(globalUserTaskListenerRegistrationHelper.registerIfEnabled()).thenThrow(IllegalStateException("registration failed"))

    assertThatThrownBy {
      binding(preloadExistingTasks = true).start(runningEvent)
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessage("registration failed")

    verify(listenerPreloadUserTaskDelivery, never()).refresh()
    verify(listenerUserTaskDelivery, never()).subscribe()
  }

  @Test
  fun `should close listener worker when stopping`() {
    binding(preloadExistingTasks = true).stop(stoppingEvent)

    verify(listenerUserTaskDelivery, times(1)).close()
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
