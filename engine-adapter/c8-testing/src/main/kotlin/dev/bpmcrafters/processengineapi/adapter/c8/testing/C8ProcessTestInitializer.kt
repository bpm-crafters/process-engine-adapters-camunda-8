package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.testing.api.ProcessTestContext
import dev.bpmcrafters.processengineapi.testing.bootstrap.ProcessTestInitializationRequest
import dev.bpmcrafters.processengineapi.testing.bootstrap.ProcessTestInitializer

/**
 * Provides the process-test API through Camunda 8's remote client APIs.
 */
class C8ProcessTestInitializer : ProcessTestInitializer {
  companion object {
    const val QUALIFIER = "c8"
  }

  override fun qualifier() = QUALIFIER

  override fun initialize(request: ProcessTestInitializationRequest): ProcessTestContext =
    C8ProcessTestContext.managed(QUALIFIER, C8ProcessTestSettings.from(request))
}
