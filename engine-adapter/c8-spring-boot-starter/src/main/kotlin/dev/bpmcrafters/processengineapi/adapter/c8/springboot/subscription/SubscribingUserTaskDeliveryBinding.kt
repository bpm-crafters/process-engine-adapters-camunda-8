package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationRunningEvent
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationStoppingEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener

private val logger = KotlinLogging.logger {}

open class SubscribingUserTaskDeliveryBinding(
  private val subscribingRefreshingZeebeJobUserTaskDelivery: SubscribingRefreshingZeebeJobUserTaskDelivery
) {

  @EventListener
  open fun start(event: ProcessApplicationRunningEvent) {
    logger.trace { "PROCESS-ENGINE-C8-102: Subscribing to user tasks..." }
    subscribingRefreshingZeebeJobUserTaskDelivery.subscribe()
    logger.trace { "PROCESS-ENGINE-C8-103: Subscribed to user tasks." }
  }

  @EventListener
  open fun stop(event: ProcessApplicationStoppingEvent) {
    subscribingRefreshingZeebeJobUserTaskDelivery.unsubscribeAll()
  }

}
