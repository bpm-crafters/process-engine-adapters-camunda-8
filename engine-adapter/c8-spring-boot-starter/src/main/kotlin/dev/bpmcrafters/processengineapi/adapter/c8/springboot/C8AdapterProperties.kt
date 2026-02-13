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
    val retryTimeoutInSeconds: Long = 5L
  )

  data class UserTasks(
    /**
     * Type of user tasks to use.
     */
    val type: UserTaskType,
    /**
     * Delivery strategy for user tasks.
     */
    val deliveryStrategy: UserTaskDeliveryStrategy,
    /**
     * Fixed rate for scheduled user task delivery.
     */
    val scheduleDeliveryFixedRateInSeconds: Long = 5L,
  )

  /**
   * Type of user tasks to use.
   */
  enum class UserTaskType {
    /**
     * Native user tasks of Camunda.
     */
    NATIVE,

    /**
     * Deprecated job worker implementation for user tasks.
     */
    JOB
  }

  /**
   * Strategy to deliver user tasks.
   */
  enum class UserTaskDeliveryStrategy {
    /**
     * Scheduled, based on task list client.
     */
    SCHEDULED,

    /**
     * Subscribing using zeebe job subscriptions, extending lock times.
     */
    SUBSCRIPTION_REFRESHING,

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
