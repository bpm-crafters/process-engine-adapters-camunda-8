package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import io.quarkus.test.QuarkusUnitTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class MissingWorkerTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = QuarkusUnitTest().withEmptyApplication()
      .overrideConfigKey("quarkus.process-engine-api.c8.enabled", "true")
      .overrideConfigKey("quarkus.process-engine-api.c8.service-tasks.delivery-strategy", "CUSTOM")
      .overrideConfigKey("quarkus.process-engine-api.c8.user-tasks.delivery-strategy", "CUSTOM")
      .assertException { error -> assertThat(error).hasStackTraceContaining(
        "quarkus.process-engine-api.c8.service-tasks.worker-id is missing or invalid"
      ) }
  }

  @Test
  fun `rejects missing worker even for custom strategy`() {
    // The extension asserts the runtime startup failure.
  }
}
