package dev.bpmcrafters.processengineapi.adapter.c8.process

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.MetaInfo
import dev.bpmcrafters.processengineapi.MetaInfoAware
import dev.bpmcrafters.processengineapi.process.*
import io.camunda.client.CamundaClient
import io.camunda.client.api.command.CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3
import io.camunda.client.api.command.PublishMessageCommandStep1.PublishMessageCommandStep3
import io.camunda.client.api.response.ProcessInstanceEvent
import io.camunda.client.api.response.PublishMessageResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger {}

class StartProcessApiImpl(
  private val camundaClient: CamundaClient
) : StartProcessApi {

  override fun startProcess(cmd: StartProcessCommand): CompletableFuture<ProcessInformation> {
    return when (cmd) {
      is StartProcessByDefinitionCmd ->
        CompletableFuture.supplyAsync {
          logger.debug { "PROCESS-ENGINE-C8-004: Starting a new process instance by definition ${cmd.definitionKey}." }
          camundaClient
            .newCreateInstanceCommand()
            .bpmnProcessId(cmd.definitionKey)
            .latestVersion()
            .variables(cmd.payloadSupplier.get())
            .applyRestrictions(ensureSupported(cmd.restrictions))
            .send()
            .get()
            .toProcessInformation()
        }

      is StartProcessByMessageCmd ->
        CompletableFuture.supplyAsync {
          logger.debug { "PROCESS-ENGINE-C8-005: Starting a new process instance by message ${cmd.messageName}." }
          camundaClient
            .newPublishMessageCommand()
            .messageName(cmd.messageName)
            .correlationKey("") // empty means create a new instance
            .variables(cmd.payloadSupplier.get())
            .applyRestrictions(ensureSupported(cmd.restrictions))
            .send()
            .get()
            .toProcessInformation()
        }

      else -> throw IllegalArgumentException("Unsupported start command $cmd")
    }
  }

  override fun meta(instance: MetaInfoAware): MetaInfo {
    TODO("Not yet implemented")
  }

  override fun getSupportedRestrictions(): Set<String> = setOf(
    CommonRestrictions.TENANT_ID
  )

  private fun CreateProcessInstanceCommandStep3.applyRestrictions(restrictions: Map<String, String>): CreateProcessInstanceCommandStep3 = this.apply {
    restrictions
      .forEach { (key, value) ->
        when (key) {
          CommonRestrictions.TENANT_ID -> if (value.isNotEmpty()) {
            this.tenantId(value)
          }
        }
      }
  }

  private fun PublishMessageCommandStep3.applyRestrictions(restrictions: Map<String, String>): PublishMessageCommandStep3 = this.apply {
    restrictions
      .forEach { (key, value) ->
        when (key) {
          CommonRestrictions.TENANT_ID -> if (value.isNotEmpty()) {
            this.tenantId(value)
          }
        }
      }
  }


  private fun ProcessInstanceEvent.toProcessInformation() = ProcessInformation(
    instanceId = "${this.processInstanceKey}",
    meta = mapOf(
      "processDefinitionId" to "${this.processDefinitionKey}",
      CommonRestrictions.PROCESS_DEFINITION_KEY to this.bpmnProcessId,
      CommonRestrictions.TENANT_ID to this.tenantId,
    )
  )

  private fun PublishMessageResponse.toProcessInformation() = ProcessInformation(
    instanceId = "",
    meta = mapOf(
      CommonRestrictions.TENANT_ID to this.tenantId
    )
  )

}
