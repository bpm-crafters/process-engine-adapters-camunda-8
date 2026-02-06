package dev.bpmcrafters.processengineapi.adapter.c8.decision

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import dev.bpmcrafters.processengineapi.decision.DecisionEvaluationOutput

class DecisionEvaluationOutputImpl(
  private val objectMapper: ObjectMapper,
  private val rawOutput: String
) : DecisionEvaluationOutput {
  companion object {
    val MAP_TYPE_REFERENCE = object : TypeReference<Map<String, Any?>>() {}
  }

  override fun asMap(): Map<String, Any?>? =
    try {
        objectMapper.readValue(rawOutput, MAP_TYPE_REFERENCE)
    } catch (e: Exception) {
      throw IllegalStateException("Can't parse into Map a decision output: $rawOutput. Ensure that it is expected to be multi-value output", e)
    }

  override fun <T : Any> asType(type: Class<T>): T? =
    try {
      if ("null" == rawOutput) {
        null
      } else {
        objectMapper.readValue(rawOutput, type)
      }
    } catch (e: Exception) {
      throw IllegalStateException("Can't deserialize into ${type.name} decision output: $rawOutput", e)
    }
}
