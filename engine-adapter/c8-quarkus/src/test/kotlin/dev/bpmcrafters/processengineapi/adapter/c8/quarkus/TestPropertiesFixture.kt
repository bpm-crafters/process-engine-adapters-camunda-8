package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import java.util.Optional

/**
 * Programmatic test implementation of the config mapping interface.
 */
internal fun testProperties(
  adapterEnabled: Boolean = true,
  serviceTaskDeliveryStrategy: C8AdapterProperties.ServiceTaskDeliveryStrategy? = C8AdapterProperties.ServiceTaskDeliveryStrategy.SUBSCRIPTION,
  serviceTaskWorkerId: String? = "test-worker",
  userTaskDeliveryStrategy: C8AdapterProperties.UserTaskDeliveryStrategy? = C8AdapterProperties.UserTaskDeliveryStrategy.SCHEDULED,
  fixedRateInSeconds: Long = 1L,
  preloadExistingTasks: Boolean = false,
  autoRegisterGlobalListener: Boolean = false
): C8AdapterProperties = object : C8AdapterProperties {

  override fun enabled(): Boolean = adapterEnabled

  override fun serviceTasks(): C8AdapterProperties.ServiceTasks = object : C8AdapterProperties.ServiceTasks {
    override fun deliveryStrategy() = Optional.ofNullable(serviceTaskDeliveryStrategy)
    override fun workerId() = Optional.ofNullable(serviceTaskWorkerId)
    override fun retries() = 3
    override fun retryTimeoutInSeconds() = 5L
    override fun lockTimeInSeconds() = 300L
  }

  override fun userTasks(): C8AdapterProperties.UserTasks = object : C8AdapterProperties.UserTasks {
    override fun deliveryStrategy() = Optional.ofNullable(userTaskDeliveryStrategy)
    override fun scheduleDeliveryFixedRateInSeconds() = fixedRateInSeconds
    override fun listener(): C8AdapterProperties.UserTaskListener = object : C8AdapterProperties.UserTaskListener {
      override fun topic() = "process-engine-user-tasks"
      override fun workerId() = "process-engine-user-tasks-worker"
      override fun maxJobsActive() = 32
      override fun streamEnabled() = true
      override fun lockTimeInSeconds() = 300L
      override fun retryTimeoutInSeconds() = 5L
      override fun autoRegisterGlobalListener() = autoRegisterGlobalListener
      override fun globalListenerId() = "process-engine-user-tasks"
      override fun globalListenerRetries() = 3
      override fun globalListenerAfterNonGlobal() = true
      override fun globalListenerPriority() = 0
      override fun preloadExistingTasks() = preloadExistingTasks
    }
  }
}
