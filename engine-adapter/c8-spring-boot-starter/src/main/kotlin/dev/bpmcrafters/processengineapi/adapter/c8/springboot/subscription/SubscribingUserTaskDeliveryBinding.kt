package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async

private val logger = KotlinLogging.logger {}

open class SubscribingUserTaskDeliveryBinding(
  private val subscribingRefreshingZeebeJobUserTaskDelivery: SubscribingRefreshingZeebeJobUserTaskDelivery
) {

  @EventListener
  @Async
  open fun scheduleUserTaskSubscription(event: ApplicationStartedEvent) {
    logger.trace { "PROCESS-ENGINE-C8-102: Subscribing to user tasks..." }
    subscribingRefreshingZeebeJobUserTaskDelivery.subscribe()
    logger.trace { "PROCESS-ENGINE-C8-103: Subscribed to user tasks." }
  }

}
