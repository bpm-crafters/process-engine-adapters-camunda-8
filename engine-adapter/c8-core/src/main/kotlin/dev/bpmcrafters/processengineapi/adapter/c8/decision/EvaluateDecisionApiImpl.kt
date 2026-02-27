package dev.bpmcrafters.processengineapi.adapter.c8.decision

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.MetaInfo
import dev.bpmcrafters.processengineapi.MetaInfoAware
import dev.bpmcrafters.processengineapi.decision.DecisionByRefEvaluationCommand
import dev.bpmcrafters.processengineapi.decision.DecisionEvaluationCommand
import dev.bpmcrafters.processengineapi.decision.DecisionEvaluationResult
import dev.bpmcrafters.processengineapi.decision.EvaluateDecisionApi
import io.camunda.client.CamundaClient
import io.camunda.client.api.command.EvaluateDecisionCommandStep1
import io.camunda.client.api.response.EvaluateDecisionResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger {}


class EvaluateDecisionApiImpl(
  private val camundaClient: CamundaClient
) : EvaluateDecisionApi {

  companion object {
    val objectMapper = jacksonObjectMapper()
  }

  override fun evaluateDecision(command: DecisionEvaluationCommand): CompletableFuture<DecisionEvaluationResult> =
    when (command) {
      is DecisionByRefEvaluationCommand -> {
        CompletableFuture.supplyAsync {
          logger.debug { "PROCESS-ENGINE-C8-015: Evaluating decision by ref ${command.decisionRef}" }
          camundaClient
            .newEvaluateDecisionCommand()
            .decisionId(command.decisionRef)
            .applyRestrictions(ensureSupported(command.restrictionSupplier.get()))
            .variables(command.payloadSupplier.get())
            .send()
            .get()
            .purgeFailed()
            .toDecisionEvaluationResult(objectMapper)
        }
      }

      else -> throw IllegalArgumentException("Unsupported evaluate decision command $command")
    }

  private fun EvaluateDecisionResponse.toDecisionEvaluationResult(objectMapper: ObjectMapper): DecisionEvaluationResult =
    EvaluateDecisionResultImpl(
      objectMapper = objectMapper,
      metaInfo = this.toMetaInfo(),
      outputDecision = this.decisionOutput
    )


  private fun EvaluateDecisionResponse.purgeFailed(): EvaluateDecisionResponse =
    this.failureMessage
      ?.takeIf { it.isNotBlank() }
      ?.let {
        throw IOException("Decision evaluation failed: $it")
      }
      ?: this


  private fun EvaluateDecisionResponse.toMetaInfo() =
    mapOf(
      "decisionDefinitionId" to this.decisionId,
      "decisionDefinitionVersion" to "${this.decisionVersion}",
      "decisionDefinitionKey" to "${this.decisionKey}",
      "decisionDefinitionKey" to "${this.decisionKey}",
      "decisionDefinitionName" to this.decisionName,
      "decisionRequirementsId" to this.decisionRequirementsId,
      "decisionRequirementsKey" to "${this.decisionRequirementsKey}",
      "failedDecisionId" to this.failedDecisionId,
      "failureMessage" to this.failureMessage,
      CommonRestrictions.TENANT_ID to this.tenantId,
    )

  private fun EvaluateDecisionCommandStep1.EvaluateDecisionCommandStep2.applyRestrictions(restrictions: Map<String, String>) = this.apply {
    restrictions
      .forEach { (key, value) ->
        when (key) {
          CommonRestrictions.TENANT_ID -> if (value.isNotEmpty()) {
            this.tenantId(value)
          }
        }
      }
  }

  override fun getSupportedRestrictions(): Set<String> = setOf(
    CommonRestrictions.TENANT_ID
  )

  override fun meta(instance: MetaInfoAware): MetaInfo {
    TODO("Not yet implemented")
  }
}
