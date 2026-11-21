package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationRunningEvent
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationStoppingEvent
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class SubscribingUserTaskDeliveryBindingTest {

  private val delivery = mock<SubscribingRefreshingZeebeJobUserTaskDelivery>()
  private val binding = SubscribingUserTaskDeliveryBinding(delivery)

  @Test
  fun `subscribes when the lifecycle reaches running`() {
    binding.start(mock<ProcessApplicationRunningEvent>())

    verify(delivery, times(1)).subscribe()
  }

  @Test
  fun `unsubscribes when the lifecycle reaches stopping`() {
    binding.stop(mock<ProcessApplicationStoppingEvent>())

    verify(delivery, times(1)).unsubscribeAll()
  }
}
