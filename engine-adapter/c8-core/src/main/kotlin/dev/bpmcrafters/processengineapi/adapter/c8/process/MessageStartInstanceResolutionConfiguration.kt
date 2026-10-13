package dev.bpmcrafters.processengineapi.adapter.c8.process

import java.time.Duration

/**
 * Controls lookup of the instance created by a message-start publication.
 */
data class MessageStartInstanceResolutionConfiguration(
  /**
   * Delay between correlated-message-subscription searches. A negative value disables lookup.
   */
  val timeout: Duration = DEFAULT_TIMEOUT,
  /**
   * Maximum time spent waiting for the correlated subscription when lookup is enabled.
   */
  val maxTimeout: Duration = DEFAULT_MAX_TIMEOUT,
) {

  companion object {
    val DEFAULT_TIMEOUT: Duration = Duration.ofMillis(100)
    val DEFAULT_MAX_TIMEOUT: Duration = Duration.ofSeconds(5)
  }

  init {
    require(timeout.isNegative || !timeout.isZero) { "Message-start timeout must be positive or negative to disable lookup." }
    require(timeout.isNegative || !maxTimeout.isNegative && !maxTimeout.isZero) {
      "Message-start max timeout must be positive when lookup is enabled."
    }
  }

  fun isEnabled(): Boolean = !timeout.isNegative
}
