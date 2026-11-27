package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

open class SubscribingUserTaskDeliveryBinding(
  private val subscribingRefreshingZeebeJobUserTaskDelivery: SubscribingRefreshingZeebeJobUserTaskDelivery
) {

  open fun start() {
    logger.trace { "PROCESS-ENGINE-C8-102: Subscribing to user tasks..." }
    subscribingRefreshingZeebeJobUserTaskDelivery.subscribe()
    logger.trace { "PROCESS-ENGINE-C8-103: Subscribed to user tasks." }
  }

  open fun stop() {
    subscribingRefreshingZeebeJobUserTaskDelivery.unsubscribeAll()
  }

}
