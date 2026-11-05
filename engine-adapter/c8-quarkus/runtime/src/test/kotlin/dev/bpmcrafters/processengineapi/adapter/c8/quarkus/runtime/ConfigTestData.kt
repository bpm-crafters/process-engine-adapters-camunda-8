package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import io.smallrye.config.SmallRyeConfigBuilder

object ConfigTestData {
  const val PREFIX = "quarkus.process-engine-api.c8."

  fun enabled(service: String, user: String, overrides: Map<String, String>): Configuration = configuration(
    mapOf(
      "enabled" to "true",
      "service-tasks.delivery-strategy" to service,
      "user-tasks.delivery-strategy" to user,
      "service-tasks.worker-id" to "test-worker"
    ) + overrides
  )

  fun configuration(values: Map<String, String>): Configuration {
    val config = SmallRyeConfigBuilder().withValidateUnknown(false)
      .withMapping(C8AdapterBuildTimeConfig::class.java).withMapping(C8AdapterRuntimeConfig::class.java)
      .withDefaultValues(values.mapKeys { (key, _) -> PREFIX + key }).build()
    return Configuration(
      config.getConfigMapping(C8AdapterBuildTimeConfig::class.java),
      config.getConfigMapping(C8AdapterRuntimeConfig::class.java)
    )
  }

  data class Configuration(val build: C8AdapterBuildTimeConfig, val runtime: C8AdapterRuntimeConfig)
}
