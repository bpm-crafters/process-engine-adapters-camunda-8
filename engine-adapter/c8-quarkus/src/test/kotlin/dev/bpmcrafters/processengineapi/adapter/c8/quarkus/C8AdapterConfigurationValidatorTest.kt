package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.Companion.DEFAULT_PREFIX
import io.smallrye.config.SmallRyeConfigBuilder
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Test

/**
 * Validates the generated config mapping implementation the way Quarkus does, so this covers the
 * part that only works if the class level constraint on the mapping interface is picked up for the
 * implementing class.
 */
class C8AdapterConfigurationValidatorTest {

  private val validator: Validator = Validation.byDefaultProvider()
    .configure()
    .messageInterpolator(ParameterMessageInterpolator())
    .buildValidatorFactory()
    .validator

  private fun mapping(vararg values: Pair<String, String>): C8AdapterProperties = SmallRyeConfigBuilder()
    .withMapping(C8AdapterProperties::class.java)
    .withDefaultValues(values.toMap())
    .build()
    .getConfigMapping(C8AdapterProperties::class.java)

  private fun messages(properties: C8AdapterProperties) = validator.validate(properties).map { it.message }

  @Test
  fun `disabled adapter needs no required properties`() {
    assertThat(messages(mapping("$DEFAULT_PREFIX.enabled" to "false"))).isEmpty()
  }

  @Test
  fun `enabled adapter reports every missing key at once`() {
    val messages = messages(mapping("$DEFAULT_PREFIX.enabled" to "true"))

    assertThat(messages).hasSize(2)
    assertThat(messages).anyMatch { it.contains("$DEFAULT_PREFIX.service-tasks.delivery-strategy") }
    assertThat(messages).anyMatch { it.contains("$DEFAULT_PREFIX.user-tasks.delivery-strategy") }
  }

  @Test
  fun `subscription service tasks require a worker id`() {
    val messages = messages(
      mapping(
        "$DEFAULT_PREFIX.enabled" to "true",
        "$DEFAULT_PREFIX.service-tasks.delivery-strategy" to "SUBSCRIPTION",
        "$DEFAULT_PREFIX.user-tasks.delivery-strategy" to "SCHEDULED"
      )
    )

    assertThat(messages).singleElement().asString().contains("$DEFAULT_PREFIX.service-tasks.worker-id")
  }

  @Test
  fun `custom service task delivery needs no worker id`() {
    val messages = messages(
      mapping(
        "$DEFAULT_PREFIX.enabled" to "true",
        "$DEFAULT_PREFIX.service-tasks.delivery-strategy" to "CUSTOM",
        "$DEFAULT_PREFIX.user-tasks.delivery-strategy" to "SCHEDULED"
      )
    )

    assertThat(messages).isEmpty()
  }

  @Test
  fun `fully configured adapter is valid`() {
    val messages = messages(
      mapping(
        "$DEFAULT_PREFIX.enabled" to "true",
        "$DEFAULT_PREFIX.service-tasks.delivery-strategy" to "SUBSCRIPTION",
        "$DEFAULT_PREFIX.service-tasks.worker-id" to "worker",
        "$DEFAULT_PREFIX.user-tasks.delivery-strategy" to "SUBSCRIPTION_REFRESHING"
      )
    )

    assertThat(messages).isEmpty()
  }

  @Test
  fun `value ranges are reported with the exact key`() {
    val messages = messages(
      mapping(
        "$DEFAULT_PREFIX.enabled" to "true",
        "$DEFAULT_PREFIX.service-tasks.delivery-strategy" to "CUSTOM",
        "$DEFAULT_PREFIX.user-tasks.delivery-strategy" to "SCHEDULED",
        "$DEFAULT_PREFIX.user-tasks.listener.max-jobs-active" to "0",
        "$DEFAULT_PREFIX.user-tasks.schedule-delivery-fixed-rate-in-seconds" to "0",
        "$DEFAULT_PREFIX.user-tasks.listener.topic" to " "
      )
    )

    assertThat(messages).hasSize(3)
    assertThat(messages).anyMatch { it.contains("user-tasks.listener.max-jobs-active") && it.contains("at least 1") }
    assertThat(messages).anyMatch { it.contains("schedule-delivery-fixed-rate-in-seconds") }
    assertThat(messages).anyMatch { it.contains("user-tasks.listener.topic") && it.contains("blank") }
  }

  @Test
  fun `a disabled adapter is not checked at all`() {
    val messages = messages(
      mapping(
        "$DEFAULT_PREFIX.enabled" to "false",
        "$DEFAULT_PREFIX.user-tasks.listener.max-jobs-active" to "0"
      )
    )

    assertThat(messages).isEmpty()
  }
}
