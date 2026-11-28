package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import io.quarkus.runtime.annotations.ConfigPhase
import io.quarkus.runtime.annotations.ConfigRoot
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.Optional

@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "quarkus.process-engine-api.c8")
interface C8AdapterRuntimeConfig {

  /** Connection settings used only by the default client. */
  fun client(): Client

  interface Client {
    /** HTTP(S) gRPC endpoint. */
    @WithDefault("http://localhost:26500")
    fun grpcAddress(): Optional<String>
    /** REST base URL without the API version suffix. */
    @WithDefault("http://localhost:8080")
    fun restAddress(): Optional<String>
    /** Prefer REST for commands that support both transports. */
    @WithDefault("true")
    fun preferRestOverGrpc(): Optional<String>
    /** Positive command request timeout. */
    @WithDefault("PT45S")
    fun requestTimeout(): Optional<String>
    /** Optional OAuth client credentials. */
    fun oauth(): OAuth
  }

  interface OAuth {
    /** Client ID, required when OAuth is selected. */
    fun clientId(): Optional<String>
    /** Client secret, required when OAuth is selected. */
    fun clientSecret(): Optional<String>
    /** OAuth token endpoint. */
    @WithDefault("https://login.cloud.camunda.io/oauth/token")
    fun authUrl(): Optional<String>
    /** Explicit token audience, required when OAuth is selected. */
    fun tokenAudience(): Optional<String>
  }

  /** External service task settings. */
  fun serviceTasks(): ServiceTasks

  /** User task settings. */
  fun userTasks(): UserTasks

  interface ServiceTasks {
    /** Required worker name when enabled, including CUSTOM mode. */
    fun workerId(): Optional<String>
    /** Number of job retries. */
    @WithDefault("3")
    fun retries(): Int
    /** Retry backoff in seconds. */
    @WithDefault("5")
    fun retryTimeoutInSeconds(): Long
    /** Service task lock duration in seconds. */
    @WithDefault("300")
    fun lockTimeInSeconds(): Long
  }

  interface UserTasks {
    /** Polling or lock refresh interval in seconds. */
    @WithDefault("5")
    fun scheduleDeliveryFixedRateInSeconds(): Long
    /** Listener delivery settings. */
    fun listener(): Listener
  }

  interface Listener {
    /** User task listener job type. */
    @WithDefault("process-engine-user-tasks")
    fun topic(): String
    /** Listener worker name. */
    @WithDefault("process-engine-user-tasks-worker")
    fun workerId(): String
    /** Maximum activated listener jobs. */
    @WithDefault("32")
    fun maxJobsActive(): Int
    /** Whether job streaming is enabled. */
    @WithDefault("true")
    fun streamEnabled(): Boolean
    /** Listener job lock duration in seconds. */
    @WithDefault("300")
    fun lockTimeInSeconds(): Long
    /** Listener failure backoff in seconds. */
    @WithDefault("5")
    fun retryTimeoutInSeconds(): Long
    /** Whether to register a global listener at startup. */
    @WithDefault("false")
    fun autoRegisterGlobalListener(): Boolean
    /** Global listener identifier. */
    @WithDefault("process-engine-user-tasks")
    fun globalListenerId(): String
    /** Global listener retries. */
    @WithDefault("3")
    fun globalListenerRetries(): Int
    /** Whether global listeners run after BPMN-level listeners. */
    @WithDefault("true")
    fun globalListenerAfterNonGlobal(): Boolean
    /** Global listener priority. */
    @WithDefault("0")
    fun globalListenerPriority(): Int
    /** Whether to preload existing native user tasks once at startup. */
    @WithDefault("true")
    fun preloadExistingTasks(): Boolean
  }

  fun validate(build: C8AdapterBuildTimeConfig) {
    if (!build.enabled()) return
    requiredWorkerId()
    requireValid(serviceTasks().retries() >= 0, "service-tasks.retries")
    requireValid(validDuration(serviceTasks().retryTimeoutInSeconds(), 0), "service-tasks.retry-timeout-in-seconds")
    if (build.serviceTasks().deliveryStrategy().orElseThrow() == C8AdapterBuildTimeConfig.ServiceStrategy.SUBSCRIPTION) {
      requireValid(validDuration(serviceTasks().lockTimeInSeconds(), 1), "service-tasks.lock-time-in-seconds")
    }
    val userStrategy = build.userTasks().deliveryStrategy().orElseThrow()
    if (userStrategy == C8AdapterBuildTimeConfig.UserStrategy.SCHEDULED ||
      userStrategy == C8AdapterBuildTimeConfig.UserStrategy.SUBSCRIPTION_REFRESHING) {
      scheduleIntervalInSeconds()
    }
    if (userStrategy == C8AdapterBuildTimeConfig.UserStrategy.LISTENER) {
      validateListener(userTasks().listener())
    }
  }

  fun requiredWorkerId(): String = serviceTasks().workerId().filter { it.isNotBlank() }.orElseThrow {
    IllegalArgumentException("quarkus.process-engine-api.c8.service-tasks.worker-id is missing or invalid")
  }

  fun scheduleIntervalInSeconds(): Long {
    val interval = userTasks().scheduleDeliveryFixedRateInSeconds()
    requireValid(interval > 0 && interval <= Long.MAX_VALUE / 2000, "user-tasks.schedule-delivery-fixed-rate-in-seconds")
    return interval
  }

  private fun validateListener(listener: Listener) {
    requireValid(listener.topic().isNotBlank(), "user-tasks.listener.topic")
    requireValid(listener.workerId().isNotBlank(), "user-tasks.listener.worker-id")
    requireValid(listener.maxJobsActive() > 0, "user-tasks.listener.max-jobs-active")
    requireValid(validDuration(listener.lockTimeInSeconds(), 1), "user-tasks.listener.lock-time-in-seconds")
    requireValid(validDuration(listener.retryTimeoutInSeconds(), 0), "user-tasks.listener.retry-timeout-in-seconds")
    if (listener.autoRegisterGlobalListener()) {
      requireValid(listener.globalListenerId().isNotBlank(), "user-tasks.listener.global-listener-id")
      requireValid(listener.globalListenerRetries() >= 0, "user-tasks.listener.global-listener-retries")
    }
  }

  private fun validDuration(seconds: Long, minimum: Long): Boolean =
    seconds >= minimum && seconds <= Long.MAX_VALUE / 1000

  private fun requireValid(valid: Boolean, property: String) {
    require(valid) { "quarkus.process-engine-api.c8.$property is missing or invalid" }
  }
}
