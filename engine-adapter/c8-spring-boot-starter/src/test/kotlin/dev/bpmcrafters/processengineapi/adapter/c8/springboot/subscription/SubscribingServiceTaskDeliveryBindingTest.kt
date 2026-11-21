package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationRunningEvent
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationStoppingEvent
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class SubscribingServiceTaskDeliveryBindingTest {

  private val delivery = mock<SubscribingServiceTaskDelivery>()
  private val binding = SubscribingServiceTaskDeliveryBinding(delivery)

  @Test
  fun `subscribes when the lifecycle reaches running`() {
    binding.start(mock<ProcessApplicationRunningEvent>())

    verify(delivery, times(1)).subscribe()
  }

  @Test
  fun `closes workers when the lifecycle reaches stopping`() {
    binding.stop(mock<ProcessApplicationStoppingEvent>())

    verify(delivery, times(1)).close()
  }
}
