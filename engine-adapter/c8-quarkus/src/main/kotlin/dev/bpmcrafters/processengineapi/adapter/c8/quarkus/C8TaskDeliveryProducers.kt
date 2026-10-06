package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.Companion.SERVICE_TASK_STRATEGY_KEY
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.Companion.USER_TASK_STRATEGY_KEY
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import io.camunda.client.CamundaClient
import io.quarkus.arc.Unremovable
import io.quarkus.arc.lookup.LookupIfProperty
import io.quarkus.arc.properties.StringValueMatch
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Produces one task delivery bean per configured delivery strategy, replacing the
 * `@ConditionalOn*DeliveryStrategy` beans of the Spring Boot starter. `CUSTOM` and an unset key
 * match nothing, so no bean exists and the application provides and subscribes its own delivery.
 *
 * The conditions are regular expressions on purpose. [LookupIfProperty] compares the raw
 * configuration string while SmallRye binds the enum through a hyphenating converter that also
 * accepts `subscription` or `subscription-refreshing`; an equality match would bind the strategy
 * and still not produce a bean, leaving an adapter that starts and delivers nothing.
 * [C8AdapterLifecycle] cross-checks the two on startup.
 *
 * Beans are `@Singleton` rather than `@ApplicationScoped` because the core delivery classes are
 * final, so no client proxy can be built for them. Being lookup conditional, they are only
 * reachable through `Instance`, never through a direct injection point.
 */
@Singleton
class C8TaskDeliveryProducers(
  private val camundaClient: CamundaClient,
  private val subscriptionRepository: SubscriptionRepository,
  private val properties: C8AdapterProperties
) {

  @Produces
  @Singleton
  @Unremovable
  @LookupIfProperty(name = SERVICE_TASK_STRATEGY_KEY, stringValue = "(?i)subscription", match = StringValueMatch.REGEX)
  fun subscribingServiceTaskDelivery(): SubscribingServiceTaskDelivery = SubscribingServiceTaskDelivery(
    camundaClient = camundaClient,
    subscriptionRepository = subscriptionRepository,
    workerId = properties.requiredServiceTaskWorkerId(),
    retryTimeoutInSeconds = properties.serviceTasks().retryTimeoutInSeconds(),
    lockDurationInSeconds = properties.serviceTasks().lockTimeInSeconds()
  )

  /**
   * Produced as the concrete type, so the single bean carries both `SubscribingUserTaskDelivery` and
   * `RefreshableDelivery`. Splitting it into two producers would open job workers on one instance
   * and refresh the other.
   */
  @Produces
  @Singleton
  @Unremovable
  @LookupIfProperty(
    name = USER_TASK_STRATEGY_KEY,
    stringValue = "(?i)subscription[-_]?refreshing",
    match = StringValueMatch.REGEX
  )
  fun refreshingUserTaskDelivery(): SubscribingRefreshingZeebeJobUserTaskDelivery =
    SubscribingRefreshingZeebeJobUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      workerId = properties.requiredServiceTaskWorkerId(),
      userTaskLockTimeoutMs = properties.userTasks().scheduleDeliveryFixedRateInSeconds() * 1000 * 2
    )

  @Produces
  @Singleton
  @Unremovable
  @LookupIfProperty(name = USER_TASK_STRATEGY_KEY, stringValue = "(?i)scheduled", match = StringValueMatch.REGEX)
  fun pullUserTaskDelivery(): PullUserTaskDelivery = PullUserTaskDelivery(
    camundaClient = camundaClient,
    subscriptionRepository = subscriptionRepository
  )

  @Produces
  @Singleton
  @Unremovable
  @LookupIfProperty(name = USER_TASK_STRATEGY_KEY, stringValue = "(?i)listener", match = StringValueMatch.REGEX)
  fun listenerUserTaskDelivery(): ListenerUserTaskDelivery = properties.userTasks().listener().let { listener ->
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

  @Produces
  @Singleton
  @Unremovable
  @LookupIfProperty(name = USER_TASK_STRATEGY_KEY, stringValue = "(?i)listener", match = StringValueMatch.REGEX)
  fun globalUserTaskListenerRegistrationHelper(): GlobalUserTaskListenerRegistrationHelper =
    properties.userTasks().listener().let { listener ->
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

  @Produces
  @Singleton
  @Unremovable
  @ListenerPreload
  @LookupIfProperty(name = USER_TASK_STRATEGY_KEY, stringValue = "(?i)listener", match = StringValueMatch.REGEX)
  fun listenerPreloadDelivery(): PullUserTaskDelivery = PullUserTaskDelivery(
    camundaClient = camundaClient,
    subscriptionRepository = subscriptionRepository
  )
}
