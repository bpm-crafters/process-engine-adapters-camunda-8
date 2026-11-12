package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class C8AdapterConfigTest {

  @Test
  fun `disabled needs no settings`() {
    val config = ConfigTestData.configuration(emptyMap())
    assertThat(config.build.enabled()).isFalse()
    assertThatCode(config.build::validate).doesNotThrowAnyException()
    assertThatCode { config.runtime.validate(config.build) }.doesNotThrowAnyException()
  }

  @Test
  fun `strategies and worker are required`() {
    val missingService = ConfigTestData.configuration(mapOf("enabled" to "true"))
    assertThatIllegalArgumentException().isThrownBy(missingService.build::validate)
      .withMessageContaining("service-tasks.delivery-strategy")
    val missingUser = ConfigTestData.configuration(mapOf("enabled" to "true", "service-tasks.delivery-strategy" to "CUSTOM"))
    assertThatIllegalArgumentException().isThrownBy(missingUser.build::validate)
      .withMessageContaining("user-tasks.delivery-strategy")
    val missingWorker = ConfigTestData.configuration(mapOf(
      "enabled" to "true", "service-tasks.delivery-strategy" to "CUSTOM", "user-tasks.delivery-strategy" to "CUSTOM"
    ))
    assertThatIllegalArgumentException().isThrownBy { missingWorker.runtime.validate(missingWorker.build) }
      .withMessageContaining("service-tasks.worker-id")
  }

  @Test
  fun `preserves operational defaults`() {
    val config = ConfigTestData.enabled("SUBSCRIPTION", "LISTENER", emptyMap()).runtime
    assertThat(config.client().oauth().clientId()).isEmpty()
    assertThat(config.client().oauth().clientSecret()).isEmpty()
    assertThat(config.client().oauth().tokenAudience()).isEmpty()
    assertThat(config.client().oauth().authUrl()).contains("https://login.cloud.camunda.io/oauth/token")
    assertThat(config.serviceTasks().retries()).isEqualTo(3)
    assertThat(config.serviceTasks().retryTimeoutInSeconds()).isEqualTo(5)
    assertThat(config.serviceTasks().lockTimeInSeconds()).isEqualTo(300)
    assertThat(config.userTasks().scheduleDeliveryFixedRateInSeconds()).isEqualTo(5)
    val listener = config.userTasks().listener()
    assertThat(listener.topic()).isEqualTo("process-engine-user-tasks")
    assertThat(listener.workerId()).isEqualTo("process-engine-user-tasks-worker")
    assertThat(listener.maxJobsActive()).isEqualTo(32)
    assertThat(listener.streamEnabled()).isTrue()
    assertThat(listener.lockTimeInSeconds()).isEqualTo(300)
    assertThat(listener.retryTimeoutInSeconds()).isEqualTo(5)
    assertThat(listener.autoRegisterGlobalListener()).isFalse()
    assertThat(listener.globalListenerId()).isEqualTo("process-engine-user-tasks")
    assertThat(listener.globalListenerRetries()).isEqualTo(3)
    assertThat(listener.globalListenerAfterNonGlobal()).isTrue()
    assertThat(listener.globalListenerPriority()).isZero()
    assertThat(listener.preloadExistingTasks()).isTrue()
  }

  @Test
  fun `maps nondefault operational settings`() {
    val settings = mapOf(
      "service-tasks.worker-id" to "custom-service",
      "service-tasks.retries" to "8",
      "service-tasks.retry-timeout-in-seconds" to "11",
      "service-tasks.lock-time-in-seconds" to "42",
      "user-tasks.schedule-delivery-fixed-rate-in-seconds" to "7",
      "user-tasks.listener.topic" to "custom-topic",
      "user-tasks.listener.worker-id" to "custom-listener",
      "user-tasks.listener.max-jobs-active" to "12",
      "user-tasks.listener.stream-enabled" to "false",
      "user-tasks.listener.lock-time-in-seconds" to "45",
      "user-tasks.listener.retry-timeout-in-seconds" to "13",
      "user-tasks.listener.auto-register-global-listener" to "true",
      "user-tasks.listener.global-listener-id" to "custom-global",
      "user-tasks.listener.global-listener-retries" to "6",
      "user-tasks.listener.global-listener-after-non-global" to "false",
      "user-tasks.listener.global-listener-priority" to "17",
      "user-tasks.listener.preload-existing-tasks" to "false"
    )
    val config = ConfigTestData.enabled("SUBSCRIPTION", "LISTENER", settings).runtime
    assertThat(config.serviceTasks().workerId()).contains("custom-service")
    assertThat(config.serviceTasks().retries()).isEqualTo(8)
    assertThat(config.serviceTasks().retryTimeoutInSeconds()).isEqualTo(11)
    assertThat(config.serviceTasks().lockTimeInSeconds()).isEqualTo(42)
    assertThat(config.userTasks().scheduleDeliveryFixedRateInSeconds()).isEqualTo(7)
    val listener = config.userTasks().listener()
    assertThat(listener.topic()).isEqualTo("custom-topic")
    assertThat(listener.workerId()).isEqualTo("custom-listener")
    assertThat(listener.maxJobsActive()).isEqualTo(12)
    assertThat(listener.streamEnabled()).isFalse()
    assertThat(listener.lockTimeInSeconds()).isEqualTo(45)
    assertThat(listener.retryTimeoutInSeconds()).isEqualTo(13)
    assertThat(listener.autoRegisterGlobalListener()).isTrue()
    assertThat(listener.globalListenerId()).isEqualTo("custom-global")
    assertThat(listener.globalListenerRetries()).isEqualTo(6)
    assertThat(listener.globalListenerAfterNonGlobal()).isFalse()
    assertThat(listener.globalListenerPriority()).isEqualTo(17)
    assertThat(listener.preloadExistingTasks()).isFalse()
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "service-tasks.retries", "service-tasks.retry-timeout-in-seconds", "service-tasks.lock-time-in-seconds",
    "user-tasks.listener.max-jobs-active", "user-tasks.listener.lock-time-in-seconds",
    "user-tasks.listener.retry-timeout-in-seconds", "user-tasks.listener.global-listener-retries"
  ])
  fun `rejects unsafe active values`(property: String) {
    val config = ConfigTestData.enabled("SUBSCRIPTION", "LISTENER", mapOf(
      property to "-1", "user-tasks.listener.auto-register-global-listener" to "true"
    ))
    assertThatIllegalArgumentException().isThrownBy { config.runtime.validate(config.build) }
      .withMessageContaining(property)
  }

  @ParameterizedTest
  @ValueSource(strings = ["0", "-1", "9223372036854775807"])
  fun `rejects invalid schedule`(value: String) {
    val config = ConfigTestData.enabled("CUSTOM", "SUBSCRIPTION_REFRESHING", mapOf(
      "user-tasks.schedule-delivery-fixed-rate-in-seconds" to value
    ))
    assertThatIllegalArgumentException().isThrownBy { config.runtime.validate(config.build) }
      .withMessageContaining("schedule-delivery-fixed-rate-in-seconds")
  }

  @Test
  fun `ignores inactive strategy settings`() {
    val config = ConfigTestData.enabled("CUSTOM", "CUSTOM", mapOf(
      "service-tasks.lock-time-in-seconds" to "-1", "user-tasks.schedule-delivery-fixed-rate-in-seconds" to "0",
      "user-tasks.listener.max-jobs-active" to "-1"
    ))
    assertThatCode { config.runtime.validate(config.build) }.doesNotThrowAnyException()
  }

  @Test
  fun `validates scheduled mode and blank worker name`() {
    val config = ConfigTestData.enabled("CUSTOM", "SCHEDULED", mapOf("service-tasks.worker-id" to " "))
    assertThatIllegalArgumentException().isThrownBy { config.runtime.validate(config.build) }
      .withMessageContaining("service-tasks.worker-id")
    val scheduled = ConfigTestData.enabled("CUSTOM", "SCHEDULED", emptyMap())
    assertThatCode { scheduled.runtime.validate(scheduled.build) }.doesNotThrowAnyException()
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "service-tasks.lock-time-in-seconds", "service-tasks.retry-timeout-in-seconds",
    "user-tasks.listener.lock-time-in-seconds", "user-tasks.listener.retry-timeout-in-seconds"
  ])
  fun `rejects duration overflow`(property: String) {
    val config = ConfigTestData.enabled("SUBSCRIPTION", "LISTENER", mapOf(property to Long.MAX_VALUE.toString()))
    assertThatIllegalArgumentException().isThrownBy { config.runtime.validate(config.build) }
      .withMessageContaining(property)
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "user-tasks.listener.topic", "user-tasks.listener.worker-id", "user-tasks.listener.global-listener-id"
  ])
  fun `rejects blank listener names`(property: String) {
    val config = ConfigTestData.enabled("CUSTOM", "LISTENER", mapOf(
      property to " ", "user-tasks.listener.auto-register-global-listener" to "true"
    ))
    assertThatIllegalArgumentException().isThrownBy { config.runtime.validate(config.build) }
      .withMessageContaining(property)
  }

  @Test
  fun `validates global registration settings when enabled`() {
    val config = ConfigTestData.enabled("CUSTOM", "LISTENER", mapOf("user-tasks.listener.auto-register-global-listener" to "true"))
    assertThatCode { config.runtime.validate(config.build) }.doesNotThrowAnyException()
  }
}
