package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.ServiceTaskDeliveryStrategy
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.UserTaskDeliveryStrategy
import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.RefreshableDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import io.camunda.client.CamundaClient
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

/**
 * Internal holder building the task deliveries for the configured delivery strategies. Replaces the
 * strategy-conditional beans of the Spring Boot starter with a runtime switch. All deliveries are
 * constructed lazily, so an application with a disabled adapter never touches the Camunda client.
 */
@Singleton
class C8AdapterBindings(
  private val camundaClient: CamundaClient,
  private val subscriptionRepository: SubscriptionRepository,
  private val properties: C8AdapterProperties
) {

  private val serviceTaskDeliveryLazy = lazy {
    when (properties.requiredServiceTaskDeliveryStrategy()) {
      ServiceTaskDeliveryStrategy.SUBSCRIPTION -> SubscribingServiceTaskDelivery(
        camundaClient = camundaClient,
        subscriptionRepository = subscriptionRepository,
        workerId = properties.requiredServiceTaskWorkerId(),
        retryTimeoutInSeconds = properties.serviceTasks().retryTimeoutInSeconds(),
        lockDurationInSeconds = properties.serviceTasks().lockTimeInSeconds()
      )

      ServiceTaskDeliveryStrategy.CUSTOM -> null
    }
  }

  private val refreshingUserTaskDeliveryLazy = lazy {
    SubscribingRefreshingZeebeJobUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      workerId = properties.requiredServiceTaskWorkerId(),
      userTaskLockTimeoutMs = properties.userTasks().scheduleDeliveryFixedRateInSeconds() * 1000 * 2
    )
  }

  private val pullUserTaskDeliveryLazy = lazy {
    PullUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository
    )
  }

  private val listenerUserTaskDeliveryLazy = lazy {
    val listener = properties.userTasks().listener()
    ListenerUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      topic = listener.topic(),
      workerId = listener.workerId(),
      maxJobsActive = listener.maxJobsActive(),
      streamEnabled = listener.streamEnabled(),
      lockTimeInSeconds = listener.lockTimeInSeconds(),
      retryTimeoutInSeconds = listener.retryTimeoutInSeconds()
    )
  }

  private val globalUserTaskListenerRegistrationHelperLazy = lazy {
    val listener = properties.userTasks().listener()
    GlobalUserTaskListenerRegistrationHelper(
      camundaClient = camundaClient,
      autoRegisterGlobalListener = listener.autoRegisterGlobalListener(),
      globalListenerId = listener.globalListenerId(),
      topic = listener.topic(),
      globalListenerRetries = listener.globalListenerRetries(),
      globalListenerAfterNonGlobal = listener.globalListenerAfterNonGlobal(),
      globalListenerPriority = listener.globalListenerPriority()
    )
  }

  /**
   * User task delivery handed over to the task subscription api, present for subscribing strategies.
   */
  val subscribingUserTaskDelivery: SubscribingUserTaskDelivery?
    get() = when (properties.requiredUserTaskDeliveryStrategy()) {
      UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING -> refreshingUserTaskDeliveryLazy.value
      UserTaskDeliveryStrategy.LISTENER -> listenerUserTaskDeliveryLazy.value
      UserTaskDeliveryStrategy.SCHEDULED, UserTaskDeliveryStrategy.CUSTOM -> null
    }

  /**
   * User task delivery refreshed at fixed rate, present for SCHEDULED and SUBSCRIPTION_REFRESHING.
   */
  val refreshableUserTaskDelivery: RefreshableDelivery?
    get() = when (properties.requiredUserTaskDeliveryStrategy()) {
      UserTaskDeliveryStrategy.SCHEDULED -> pullUserTaskDeliveryLazy.value
      UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING -> refreshingUserTaskDeliveryLazy.value
      UserTaskDeliveryStrategy.LISTENER, UserTaskDeliveryStrategy.CUSTOM -> null
    }

  /**
   * Subscribes the configured deliveries, mirroring the startup bindings of the Spring Boot starter.
   */
  fun start() {
    startServiceTasks()
    startUserTasks()
  }

  /**
   * Subscribes the configured service task delivery.
   */
  fun startServiceTasks() {
    when (properties.requiredServiceTaskDeliveryStrategy()) {
      ServiceTaskDeliveryStrategy.SUBSCRIPTION -> {
        logger.trace { "PROCESS-ENGINE-C8-100: Subscribing to service tasks..." }
        serviceTaskDeliveryLazy.value?.subscribe()
        logger.trace { "PROCESS-ENGINE-C8-101: Subscribed to service tasks." }
      }

      ServiceTaskDeliveryStrategy.CUSTOM -> Unit
    }
  }

  /**
   * Subscribes the configured user task delivery.
   */
  fun startUserTasks() {
    when (properties.requiredUserTaskDeliveryStrategy()) {
      UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING -> {
        logger.trace { "PROCESS-ENGINE-C8-102: Subscribing to user tasks..." }
        refreshingUserTaskDeliveryLazy.value.subscribe()
        logger.trace { "PROCESS-ENGINE-C8-103: Subscribed to user tasks." }
      }

      UserTaskDeliveryStrategy.LISTENER -> {
        logger.trace { "PROCESS-ENGINE-C8-111: Registering global user task listener if enabled..." }
        globalUserTaskListenerRegistrationHelperLazy.value.registerIfEnabled()
        logger.trace { "PROCESS-ENGINE-C8-112: Global user task listener registration checked." }
        if (properties.userTasks().listener().preloadExistingTasks()) {
          try {
            logger.trace { "PROCESS-ENGINE-C8-113: Preloading existing user tasks for listener delivery..." }
            pullUserTaskDeliveryLazy.value.refresh()
            logger.trace { "PROCESS-ENGINE-C8-114: Preloaded existing user tasks for listener delivery." }
          } catch (e: Exception) {
            logger.error(e) { "PROCESS-ENGINE-C8-115: Failed to preload existing user tasks for listener delivery." }
          }
        }
        logger.trace { "PROCESS-ENGINE-C8-104: Subscribing to user task listener jobs..." }
        listenerUserTaskDeliveryLazy.value.subscribe()
        logger.trace { "PROCESS-ENGINE-C8-105: Subscribed to user task listener jobs." }
      }

      UserTaskDeliveryStrategy.SCHEDULED, UserTaskDeliveryStrategy.CUSTOM -> Unit
    }
  }

  /**
   * Closes deliveries holding resources. Only deliveries that were actually constructed are closed.
   */
  fun close() {
    if (listenerUserTaskDeliveryLazy.isInitialized()) {
      listenerUserTaskDeliveryLazy.value.close()
    }
  }
}
