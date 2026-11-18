package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import io.quarkus.test.QuarkusUnitTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class DefaultClientStartupFailureTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = ClientLifecycleTestSupport.application(fail = true)
  }

  @Test
  fun `failed startup preserves original failure and disposes resources`() {
    // assertException checks the original and suppressed cleanup failure; after-undeploy checks disposal.
  }
}
