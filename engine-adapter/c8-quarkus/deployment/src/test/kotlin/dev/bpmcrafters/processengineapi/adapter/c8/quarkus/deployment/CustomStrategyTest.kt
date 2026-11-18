package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import io.quarkus.test.QuarkusUnitTest
import org.junit.jupiter.api.extension.RegisterExtension

class CustomStrategyTest : StrategyTestSupport() {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = StrategyTestSupport.application("CUSTOM", "CUSTOM")
  }
}
