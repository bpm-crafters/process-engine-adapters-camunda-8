package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationRunningEvent
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationStoppingEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener

private val logger = KotlinLogging.logger {}

open class SubscribingServiceTaskDeliveryBinding(
  private val subscribingServiceTaskDelivery: SubscribingServiceTaskDelivery
) {

  @EventListener
  open fun start(event: ProcessApplicationRunningEvent) {
    logger.trace { "PROCESS-ENGINE-C8-100: Subscribing to service tasks..." }
    subscribingServiceTaskDelivery.subscribe()
    logger.trace { "PROCESS-ENGINE-C8-101: Subscribed to service tasks." }
  }

  @EventListener
  open fun stop(event: ProcessApplicationStoppingEvent) {
    subscribingServiceTaskDelivery.close()
  }

}
