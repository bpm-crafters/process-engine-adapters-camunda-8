package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async

private val logger = KotlinLogging.logger {}

open class UserTaskListenerDeliveryBinding(
  private val listenerUserTaskDelivery: ListenerUserTaskDelivery,
  private val listenerPreloadUserTaskDelivery: PullUserTaskDelivery,
  private val globalUserTaskListenerRegistrationHelper: GlobalUserTaskListenerRegistrationHelper,
  private val c8AdapterProperties: C8AdapterProperties,
) {

  @EventListener
  @Async
  open fun scheduleUserTaskListenerSubscription(event: ApplicationStartedEvent) {
    logger.trace { "PROCESS-ENGINE-C8-111: Registering global user task listener if enabled..." }
    globalUserTaskListenerRegistrationHelper.registerIfEnabled()
    logger.trace { "PROCESS-ENGINE-C8-112: Global user task listener registration checked." }
    if (c8AdapterProperties.userTasks.listener.preloadExistingTasks) {
      try {
        logger.trace { "PROCESS-ENGINE-C8-113: Preloading existing user tasks for listener delivery..." }
        listenerPreloadUserTaskDelivery.refresh()
        logger.trace { "PROCESS-ENGINE-C8-114: Preloaded existing user tasks for listener delivery." }
      } catch (e: Exception) {
        logger.error(e) { "PROCESS-ENGINE-C8-115: Failed to preload existing user tasks for listener delivery." }
      }
    }
    logger.trace { "PROCESS-ENGINE-C8-104: Subscribing to user task listener jobs..." }
    listenerUserTaskDelivery.subscribe()
    logger.trace { "PROCESS-ENGINE-C8-105: Subscribed to user task listener jobs." }
  }
}
