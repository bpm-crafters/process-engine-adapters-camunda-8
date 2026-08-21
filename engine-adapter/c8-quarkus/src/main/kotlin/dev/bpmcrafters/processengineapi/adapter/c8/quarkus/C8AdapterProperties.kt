package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.Companion.DEFAULT_PREFIX
import io.quarkus.arc.Unremovable
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.Optional

/**
 * Configuration of Camunda 8 adapter.
 *
 * Uses the same property tree as the Spring Boot starter (see `docs/reference-c8.md`). Properties
 * that are required by the Spring Boot starter are modelled as [Optional] here, because a config
 * mapping is validated on application start even if the adapter is disabled. They are validated
 * as soon as the adapter is enabled.
 */
@ConfigMapping(prefix = DEFAULT_PREFIX)
@Unremovable
interface C8AdapterProperties {

  companion object {
    const val DEFAULT_PREFIX = "dev.bpm-crafters.process-api.adapter.c8"
  }

  /**
   * Flag controlling if the entire adapter is active. In contrast to Spring, the flag defaults to
   * `false` resulting in the same effective behavior: the adapter stays inert unless the property
   * is explicitly set to `true`.
   */
  @WithDefault("false")
  fun enabled(): Boolean

  /**
   * Configuration for external service tasks.
   */
  fun serviceTasks(): ServiceTasks

  /**
   * Configuration of user tasks.
   */
  fun userTasks(): UserTasks

  interface ServiceTasks {
    /**
     * Delivery strategy for service tasks. Required if the adapter is enabled.
     */
    fun deliveryStrategy(): Optional<ServiceTaskDeliveryStrategy>

    /**
     * Default id of the worker used for the external task. Required if the adapter is enabled.
     */
    fun workerId(): Optional<String>

    /**
     * Number of job retries.
     */
    @WithDefault("3")
    fun retries(): Int

    /**
     * Timeout in seconds before making a retry.
     */
    @WithDefault("5")
    fun retryTimeoutInSeconds(): Long

    /**
     * Time in seconds to lock a service task. Default is 5 minutes.
     */
    @WithDefault("300")
    fun lockTimeInSeconds(): Long
  }

  interface UserTasks {
    /**
     * Delivery strategy for user tasks. Required if the adapter is enabled.
     */
    fun deliveryStrategy(): Optional<UserTaskDeliveryStrategy>

    /**
     * Fixed rate for scheduled user task delivery.
     */
    @WithDefault("5")
    fun scheduleDeliveryFixedRateInSeconds(): Long

    /**
     * Listener-based user task delivery configuration.
     */
    fun listener(): UserTaskListener
  }

  interface UserTaskListener {
    /**
     * User task listener job type.
     */
    @WithDefault("process-engine-user-tasks")
    fun topic(): String

    /**
     * Worker id used by the listener job worker.
     */
    @WithDefault("process-engine-user-tasks-worker")
    fun workerId(): String

    /**
     * Maximum listener jobs activated by the worker.
     */
    @WithDefault("32")
    fun maxJobsActive(): Int

    /**
     * Enables Camunda job streaming for listener jobs.
     */
    @WithDefault("true")
    fun streamEnabled(): Boolean

    /**
     * Time in seconds to lock a listener job.
     */
    @WithDefault("300")
    fun lockTimeInSeconds(): Long

    /**
     * Timeout in seconds before making a retry after listener delivery failure.
     */
    @WithDefault("5")
    fun retryTimeoutInSeconds(): Long

    /**
     * Enables global listener auto-registration on startup.
     */
    @WithDefault("false")
    fun autoRegisterGlobalListener(): Boolean

    /**
     * Id used for global listener auto-registration.
     */
    @WithDefault("process-engine-user-tasks")
    fun globalListenerId(): String

    /**
     * Number of retries configured for the global listener.
     */
    @WithDefault("3")
    fun globalListenerRetries(): Int

    /**
     * Configures the global listener to run after BPMN-level listeners.
     */
    @WithDefault("true")
    fun globalListenerAfterNonGlobal(): Boolean

    /**
     * Priority configured for the global listener.
     */
    @WithDefault("0")
    fun globalListenerPriority(): Int

    /**
     * Enables one-shot startup preload of already-created user tasks.
     */
    @WithDefault("true")
    fun preloadExistingTasks(): Boolean
  }

  /**
   * Strategy to deliver user tasks.
   */
  enum class UserTaskDeliveryStrategy {
    /**
     * Scheduled, based on native camunda tasks.
     */
    SCHEDULED,

    /**
     * Subscribing using zeebe job subscriptions, extending lock times.
     */
    SUBSCRIPTION_REFRESHING,

    /**
     * Subscribing using Camunda user task listener jobs.
     */
    LISTENER,

    /**
     * Own strategy.
     */
    CUSTOM
  }

  /**
   * Strategy to deliver external service tasks.
   */
  enum class ServiceTaskDeliveryStrategy {
    /**
     * Subscribing using camunda job.
     */
    SUBSCRIPTION,

    /**
     * Own strategy.
     */
    CUSTOM
  }
}

/**
 * Guards adapter beans against usage while the adapter is disabled.
 */
fun C8AdapterProperties.requireEnabled() {
  check(enabled()) {
    "The Camunda 8 process engine adapter is disabled. Set '$DEFAULT_PREFIX.enabled' to 'true' to activate it."
  }
}

/**
 * Returns the configured service task delivery strategy, failing fast with the exact missing key.
 */
fun C8AdapterProperties.requiredServiceTaskDeliveryStrategy(): C8AdapterProperties.ServiceTaskDeliveryStrategy =
  serviceTasks().deliveryStrategy().orElseThrow { missingKey("service-tasks.delivery-strategy") }

/**
 * Returns the configured service task worker id, failing fast with the exact missing key.
 */
fun C8AdapterProperties.requiredServiceTaskWorkerId(): String =
  serviceTasks().workerId().orElseThrow { missingKey("service-tasks.worker-id") }

/**
 * Returns the configured user task delivery strategy, failing fast with the exact missing key.
 */
fun C8AdapterProperties.requiredUserTaskDeliveryStrategy(): C8AdapterProperties.UserTaskDeliveryStrategy =
  userTasks().deliveryStrategy().orElseThrow { missingKey("user-tasks.delivery-strategy") }

private fun missingKey(key: String) = IllegalStateException(
  "The Camunda 8 process engine adapter is enabled but '$DEFAULT_PREFIX.$key' is not set."
)
