package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.testing.bootstrap.ProcessTestInitializationRequest
import java.time.Duration

/**
 * Settings resolved for C8 process-test infrastructure.
 */
data class C8ProcessTestSettings(
  val deploymentPropagationDelay: Duration = DEFAULT_DEPLOYMENT_PROPAGATION_DELAY,
) {
  init {
    require(!deploymentPropagationDelay.isNegative) { "Deployment propagation delay must not be negative." }
  }

  companion object {
    const val DEPLOYMENT_PROPAGATION_DELAY_CONFIGURATION = "c8.deployment-propagation-delay"
    const val DEPLOYMENT_PROPAGATION_DELAY_PROPERTY = "bpmcrafters.c8.testing.deployment-propagation-delay"
    val DEFAULT_DEPLOYMENT_PROPAGATION_DELAY: Duration = Duration.ofSeconds(1)

    fun from(request: ProcessTestInitializationRequest): C8ProcessTestSettings = C8ProcessTestSettings(
      deploymentPropagationDelay = request.configuration[DEPLOYMENT_PROPAGATION_DELAY_CONFIGURATION]
        ?.let(Duration::parse)
        ?: System.getProperty(DEPLOYMENT_PROPAGATION_DELAY_PROPERTY)
          ?.let(Duration::parse)
        ?: DEFAULT_DEPLOYMENT_PROPAGATION_DELAY,
    )
  }
}
