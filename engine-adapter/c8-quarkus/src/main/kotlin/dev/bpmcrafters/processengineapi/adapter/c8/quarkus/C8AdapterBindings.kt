package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.enterprise.inject.Instance
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

/**
 * Subscribes the task deliveries that [C8TaskDeliveryProducers] produced for the configured
 * strategies. Whether a delivery exists is decided by the bean conditions, so this class only
 * decides what to call on the deliveries that are there.
 */
@Singleton
class C8AdapterBindings(
  private val serviceTaskDelivery: Instance<SubscribingServiceTaskDelivery>,
  private val refreshingUserTaskDelivery: Instance<SubscribingRefreshingZeebeJobUserTaskDelivery>,
  private val pullUserTaskDelivery: Instance<PullUserTaskDelivery>,
  private val listenerUserTaskDelivery: Instance<ListenerUserTaskDelivery>,
  private val globalUserTaskListenerRegistrationHelper: Instance<GlobalUserTaskListenerRegistrationHelper>,
  @param:ListenerPreload private val listenerPreloadDelivery: Instance<PullUserTaskDelivery>,
  private val properties: C8AdapterProperties
) {

  /**
   * Delivery that was actually subscribed and needs to be closed on shutdown. Written on the
   * subscribing thread, read on the shutdown thread.
   */
  @Volatile
  private var startedListenerDelivery: AutoCloseable? = null

  /**
   * Whether the configured service task strategy produced a delivery.
   */
  fun hasServiceTaskDelivery(): Boolean = serviceTaskDelivery.isResolvable

  /**
   * Whether the configured user task strategy produced a delivery.
   */
  fun hasUserTaskDelivery(): Boolean =
    refreshingUserTaskDelivery.isResolvable || listenerUserTaskDelivery.isResolvable || pullUserTaskDelivery.isResolvable

  /**
   * Subscribes the configured deliveries, mirroring the startup bindings of the Spring Boot starter.
   */
  fun start() {
    startServiceTasks()
    startUserTasks()
  }

  /**
   * Subscribes the service task delivery, if the configured strategy produced one.
   */
  fun startServiceTasks() {
    if (serviceTaskDelivery.isResolvable) {
      logger.trace { "PROCESS-ENGINE-C8-100: Subscribing to service tasks..." }
      serviceTaskDelivery.get().subscribe()
      logger.trace { "PROCESS-ENGINE-C8-101: Subscribed to service tasks." }
    }
  }

  /**
   * Subscribes the user task delivery, if the configured strategy produced one.
   */
  fun startUserTasks() {
    if (refreshingUserTaskDelivery.isResolvable) {
      logger.trace { "PROCESS-ENGINE-C8-102: Subscribing to user tasks..." }
      refreshingUserTaskDelivery.get().subscribe()
      logger.trace { "PROCESS-ENGINE-C8-103: Subscribed to user tasks." }
    }
    if (listenerUserTaskDelivery.isResolvable) {
      logger.trace { "PROCESS-ENGINE-C8-111: Registering global user task listener if enabled..." }
      globalUserTaskListenerRegistrationHelper.get().registerIfEnabled()
      logger.trace { "PROCESS-ENGINE-C8-112: Global user task listener registration checked." }
      if (properties.userTasks().listener().preloadExistingTasks()) {
        try {
          logger.trace { "PROCESS-ENGINE-C8-113: Preloading existing user tasks for listener delivery..." }
          listenerPreloadDelivery.get().refresh()
          logger.trace { "PROCESS-ENGINE-C8-114: Preloaded existing user tasks for listener delivery." }
        } catch (e: Exception) {
          logger.error(e) { "PROCESS-ENGINE-C8-115: Failed to preload existing user tasks for listener delivery." }
        }
      }
      logger.trace { "PROCESS-ENGINE-C8-104: Subscribing to user task listener jobs..." }
      val delivery = listenerUserTaskDelivery.get()
      delivery.subscribe()
      startedListenerDelivery = delivery
      logger.trace { "PROCESS-ENGINE-C8-105: Subscribed to user task listener jobs." }
    }
  }

  /**
   * Closes deliveries holding resources. Only deliveries that were actually subscribed are closed.
   */
  fun close() {
    startedListenerDelivery?.close()
    startedListenerDelivery = null
  }
}
