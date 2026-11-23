package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

open class SubscribingServiceTaskDeliveryBinding(
  private val subscribingServiceTaskDelivery: SubscribingServiceTaskDelivery
) {

  open fun start() {
    logger.trace { "PROCESS-ENGINE-C8-100: Subscribing to service tasks..." }
    subscribingServiceTaskDelivery.subscribe()
    logger.trace { "PROCESS-ENGINE-C8-101: Subscribed to service tasks." }
  }

  open fun stop() {
    subscribingServiceTaskDelivery.close()
  }

}
