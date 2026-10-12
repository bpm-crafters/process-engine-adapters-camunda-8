package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.Companion.DEFAULT_PREFIX
import io.smallrye.config.SmallRyeConfigBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Binds the config mapping through SmallRye itself. `build()` constructs and validates every
 * registered mapping, so this fails as soon as one of the default accessors is mistaken for a
 * configuration property — the failure mode a `-jvm-default=disable` build would introduce.
 */
class C8AdapterPropertiesMappingTest {

  private fun config(vararg values: Pair<String, String>) = SmallRyeConfigBuilder()
    .withMapping(C8AdapterProperties::class.java)
    .withDefaultValues(values.toMap())
    .build()

  @Test
  fun `mapping binds with only the enabled flag`() {
    assertThatCode { config("$DEFAULT_PREFIX.enabled" to "false") }.doesNotThrowAnyException()
  }

  @Test
  fun `default accessors are not exposed as configuration properties`() {
    val config = config("$DEFAULT_PREFIX.enabled" to "false")
    val ownKeys = config.propertyNames.filter { it.startsWith(DEFAULT_PREFIX) }
    assertThat(ownKeys).noneMatch { it.contains("required") || it.contains("require-enabled") }
  }

  @Test
  fun `mapping binds the configured strategies`() {
    val properties = config(
      "$DEFAULT_PREFIX.enabled" to "true",
      "$DEFAULT_PREFIX.service-tasks.delivery-strategy" to "SUBSCRIPTION",
      "$DEFAULT_PREFIX.service-tasks.worker-id" to "worker",
      "$DEFAULT_PREFIX.user-tasks.delivery-strategy" to "SCHEDULED"
    ).getConfigMapping(C8AdapterProperties::class.java)

    assertThat(properties.enabled()).isTrue()
    assertThat(properties.requiredServiceTaskDeliveryStrategy())
      .isEqualTo(C8AdapterProperties.ServiceTaskDeliveryStrategy.SUBSCRIPTION)
    assertThat(properties.requiredServiceTaskWorkerId()).isEqualTo("worker")
    assertThat(properties.requiredUserTaskDeliveryStrategy())
      .isEqualTo(C8AdapterProperties.UserTaskDeliveryStrategy.SCHEDULED)
  }

  @Test
  fun `mapping accepts the lower case and hyphenated spelling of a strategy`() {
    val properties = config(
      "$DEFAULT_PREFIX.enabled" to "true",
      "$DEFAULT_PREFIX.service-tasks.delivery-strategy" to "subscription",
      "$DEFAULT_PREFIX.service-tasks.worker-id" to "worker",
      "$DEFAULT_PREFIX.user-tasks.delivery-strategy" to "subscription-refreshing"
    ).getConfigMapping(C8AdapterProperties::class.java)

    assertThat(properties.requiredServiceTaskDeliveryStrategy())
      .isEqualTo(C8AdapterProperties.ServiceTaskDeliveryStrategy.SUBSCRIPTION)
    assertThat(properties.requiredUserTaskDeliveryStrategy())
      .isEqualTo(C8AdapterProperties.UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING)
  }
}
