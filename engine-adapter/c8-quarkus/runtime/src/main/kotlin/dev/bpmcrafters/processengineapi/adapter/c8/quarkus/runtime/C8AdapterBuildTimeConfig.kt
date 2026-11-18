package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import io.quarkus.runtime.annotations.ConfigPhase
import io.quarkus.runtime.annotations.ConfigRoot
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.Optional

@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
@ConfigMapping(prefix = "quarkus.process-engine-api.c8")
interface C8AdapterBuildTimeConfig {

  /** Whether the Process Engine API adapter beans are enabled. */
  @WithDefault("false")
  fun enabled(): Boolean

  /** External service task configuration. */
  fun serviceTasks(): ServiceTasks

  /** User task configuration. */
  fun userTasks(): UserTasks

  interface ServiceTasks {
    /** Required delivery strategy when the adapter is enabled. */
    fun deliveryStrategy(): Optional<ServiceStrategy>
  }

  interface UserTasks {
    /** Required delivery strategy when the adapter is enabled. */
    fun deliveryStrategy(): Optional<UserStrategy>
  }

  enum class ServiceStrategy { SUBSCRIPTION, CUSTOM }

  enum class UserStrategy { SCHEDULED, SUBSCRIPTION_REFRESHING, LISTENER, CUSTOM }

  fun validate() {
    if (!enabled()) return
    requireStrategy(serviceTasks().deliveryStrategy(), "service-tasks")
    requireStrategy(userTasks().deliveryStrategy(), "user-tasks")
  }

  private fun requireStrategy(strategy: Optional<*>, group: String) {
    require(strategy.isPresent) {
      "quarkus.process-engine-api.c8.$group.delivery-strategy is required when the adapter is enabled"
    }
  }
}
