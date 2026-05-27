package dev.bpmcrafters.processengineapi.adapter.c8.task

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VariableUtilTest {

  @Test
  fun `should parse variables back to JSON`() {
    val input = mapOf(
      "stringValue" to "\"test\"",
      "intValue" to "123",
      "nullValue" to "null",
      "listVariable" to "[\"element1\",\"element2\"]",
      "toBeOrNotToBe" to "true",
      "listIntVariable" to "[12,13,15]",
      "complex" to "{\"foo\":\"foo\",\"bar\":\"bar\"}",
      "action1" to "\"value1\""
    )

    val result = input.asJson()

    // The result should be a single JSON string
    // Note: the order of keys in JSON might vary, so we might need to parse it back to compare or check properties
    assertThat(result).contains("\"stringValue\":\"test\"")
    assertThat(result).contains("\"intValue\":123")
    assertThat(result).contains("\"listVariable\":[\"element1\",\"element2\"]")
    assertThat(result).contains("\"toBeOrNotToBe\":true")
    assertThat(result).contains("\"listIntVariable\":[12,13,15]")
    assertThat(result).contains("\"complex\":{\"foo\":\"foo\",\"bar\":\"bar\"}")
    assertThat(result).contains("\"action1\":\"value1\"")
    assertThat(result).contains("\"nullValue\":null")

    // Ensure it's valid JSON
    assertThat(result).startsWith("{").endsWith("}")

    val map = result.parseJson(jacksonObjectMapper())
    assertThat(map).isNotNull
  }
}
