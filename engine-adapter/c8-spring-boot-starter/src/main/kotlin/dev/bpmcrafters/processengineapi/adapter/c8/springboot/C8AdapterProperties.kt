package dev.bpmcrafters.processengineapi.adapter.c8.springboot

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties.Companion.DEFAULT_PREFIX
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

/**
 * Configuration of Camunda 8 adapter.
 */
@ConfigurationProperties(prefix = DEFAULT_PREFIX)
class C8AdapterProperties(
  /**
   * Flag controlling if the entire adapter is active.
   */
  val enabled: Boolean = true,
  /**
   * Configuration for external service tasks.
   */
  @NestedConfigurationProperty
  val serviceTasks: ServiceTasks,

  /**
   * Configuration of user tasks.
   */
  @NestedConfigurationProperty
  val userTasks: UserTasks
) {

  companion object {
    const val DEFAULT_PREFIX = "dev.bpm-crafters.process-api.adapter.c8"
  }

  class ServiceTasks(
    /**
     * Delivery strategy for user tasks.
     */
    val deliveryStrategy: ServiceTaskDeliveryStrategy,
    /**
     * Default id of the worker used for the external task.
     */
    val workerId: String,

    /**
     * Number of job retries.
     */
    val retries: Int = 3,

    /**
     * Timeout in seconds before making a retry.
     */
    val retryTimeoutInSeconds: Long = 5L,

    /**
     * Time in seconds to lock a service task. Default is 5 minutes.
     */
    val lockTimeInSeconds: Long = 300L,
  )

  data class UserTasks(
    /**
     * Delivery strategy for user tasks.
     */
    val deliveryStrategy: UserTaskDeliveryStrategy,
    /**
     * Fixed rate for scheduled user task delivery.
     */
    val scheduleDeliveryFixedRateInSeconds: Long = 5L,
    /**
     * Listener-based user task delivery configuration.
     */
    @NestedConfigurationProperty
    val listener: UserTaskListener = UserTaskListener(),
  )

  data class UserTaskListener(
    /**
     * User task listener job type.
     */
    val topic: String = "process-engine-user-tasks",
    /**
     * Worker id used by the listener job worker.
     */
    val workerId: String = "process-engine-user-tasks-worker",
    /**
     * Maximum listener jobs activated by the worker.
     */
    val maxJobsActive: Int = 32,
    /**
     * Enables Camunda job streaming for listener jobs.
     */
    val streamEnabled: Boolean = true,
    /**
     * Time in seconds to lock a listener job.
     */
    val lockTimeInSeconds: Long = 300L,
    /**
     * Timeout in seconds before making a retry after listener delivery failure.
     */
    val retryTimeoutInSeconds: Long = 5L,
    /**
     * Enables global listener auto-registration on startup.
     */
    val autoRegisterGlobalListener: Boolean = false,
    /**
     * Id used for global listener auto-registration.
     */
    val globalListenerId: String = "process-engine-user-tasks",
    /**
     * Number of retries configured for the global listener.
     */
    val globalListenerRetries: Int = 3,
    /**
     * Configures the global listener to run after BPMN-level listeners.
     */
    val globalListenerAfterNonGlobal: Boolean = true,
    /**
     * Priority configured for the global listener.
     */
    val globalListenerPriority: Int = 0,
  )

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
