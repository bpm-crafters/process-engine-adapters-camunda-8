package dev.bpmcrafters.processengineapi.adapter.c8.decision

import com.fasterxml.jackson.databind.ObjectMapper
import dev.bpmcrafters.processengineapi.decision.DecisionEvaluationOutput
import dev.bpmcrafters.processengineapi.decision.DecisionEvaluationResult

class EvaluateDecisionResultImpl(
  private val objectMapper: ObjectMapper,
  private val outputDecision: String?,
  private val metaInfo: Map<String, String> = emptyMap()
) : DecisionEvaluationResult {
  companion object {
    const val NULL_VALUE = "null"
  }

  override fun asSingle(): DecisionEvaluationOutput = DecisionEvaluationOutputImpl(objectMapper, this.outputDecision?:NULL_VALUE)

  override fun asList(): List<DecisionEvaluationOutput> =
    try {
     val rootNode = objectMapper.readTree(this.outputDecision?:NULL_VALUE)
     if (!(rootNode.isArray || rootNode.isNull)){
       throw IllegalStateException("No array found")
     }
       rootNode.map {DecisionEvaluationOutputImpl(objectMapper,
         if (it.isTextual) {it.asText()} else {it.toString()}
         )}.toList()
    } catch (e: Exception) {
      throw IllegalStateException("The result cannot be interpreted as List", e)
    }


  override fun meta(): Map<String, String> = metaInfo

}
