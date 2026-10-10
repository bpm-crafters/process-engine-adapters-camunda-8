package dev.bpmcrafters.processengineapi.adapter.c8.retry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class RetryerTest {

  @Test
  fun `retries an action until its assertion succeeds`() {
    var attempts = 0

    val result = Retryer(Duration.ofMillis(1), Duration.ofSeconds(1)).execute(
      action = { ++attempts },
      assertion = { require(it == 3) },
    )

    assertThat(result).isEqualTo(3)
    assertThat(attempts).isEqualTo(3)
  }

  @Test
  fun `executes an action once when retries are disabled`() {
    var attempts = 0

    val result = Retryer(Duration.ofMillis(-1), Duration.ZERO).execute(
      action = { ++attempts },
      assertion = { require(it == 1) },
    )

    assertThat(result).isEqualTo(1)
    assertThat(attempts).isEqualTo(1)
  }
}
