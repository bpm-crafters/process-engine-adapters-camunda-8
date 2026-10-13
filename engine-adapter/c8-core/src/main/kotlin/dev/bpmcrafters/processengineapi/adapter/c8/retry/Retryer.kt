package dev.bpmcrafters.processengineapi.adapter.c8.retry

import java.time.Duration

/**
 * Repeats an action until its assertion succeeds or the maximum timeout elapses.
 */
internal class Retryer(
  private val timeout: Duration,
  private val maxTimeout: Duration,
) {
  init {
    require(timeout.isNegative || !timeout.isZero) { "Retry timeout must be positive or negative to disable retries." }
    require(timeout.isNegative || !maxTimeout.isNegative && !maxTimeout.isZero) {
      "Retry max timeout must be positive when retries are enabled."
    }
  }

  fun <T> execute(action: () -> T, assertion: (T) -> Unit): T {
    if (timeout.isNegative) {
      return action().also(assertion)
    }

    val deadline = System.nanoTime() + maxTimeout.toNanos()
    var lastFailure: Throwable? = null
    while (true) {
      try {
        return action().also(assertion)
      } catch (failure: Throwable) {
        if (failure !is Exception && failure !is AssertionError) {
          throw failure
        }
        lastFailure = failure
        if (System.nanoTime() >= deadline) {
          throw IllegalStateException("Timed out after $maxTimeout waiting for the retry assertion to pass.", lastFailure)
        }
        Thread.sleep(timeout.toMillis())
      }
    }
  }
}
