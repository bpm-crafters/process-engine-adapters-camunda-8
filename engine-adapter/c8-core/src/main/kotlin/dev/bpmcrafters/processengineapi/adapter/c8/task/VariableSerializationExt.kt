package dev.bpmcrafters.processengineapi.adapter.c8.task

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Parses a map of string-represented variables back into a single JSON string.
 * Each value in the input map is a string representation of the original value
 * (e.g., a quoted string for strings, unquoted for numbers/booleans, or a JSON string for objects/arrays).
 */
fun Map<String, String>.asJson(): String {
  val jsonParts = this.entries.joinToString(",") { (key, value) ->
    "\"$key\":$value"
  }
  return "{$jsonParts}"
}

/**
 * Parses a JSON string into a map of string-represented variables.
 * Each value in the output map is a string representation of the original value
 * (e.g., a quoted string for strings, unquoted for numbers/booleans, or a JSON string for objects/arrays).
 * @param objectMapper Jackson ObjectMapper to use for parsing.
 * @return Map of string-represented variables.
 */
fun String.parseJson(objectMapper: ObjectMapper): Map<String, Any?> =
  objectMapper.readValue<Map<String, Any?>>(this)

