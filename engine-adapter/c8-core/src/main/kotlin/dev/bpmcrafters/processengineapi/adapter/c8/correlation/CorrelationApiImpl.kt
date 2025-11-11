package dev.bpmcrafters.processengineapi.adapter.c8.correlation

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.Empty
import dev.bpmcrafters.processengineapi.MetaInfo
import dev.bpmcrafters.processengineapi.MetaInfoAware
import dev.bpmcrafters.processengineapi.correlation.CorrelateMessageCmd
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi
import io.camunda.client.CamundaClient
import io.camunda.client.api.command.PublishMessageCommandStep1.PublishMessageCommandStep3
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger {}

class CorrelationApiImpl(
  private val camundaClient: CamundaClient
) : CorrelationApi {

  override fun correlateMessage(cmd: CorrelateMessageCmd): CompletableFuture<Empty> {
    return CompletableFuture.supplyAsync {
      val correlationKey = cmd.correlation.get().correlationKey
      logger.debug { "PROCESS-ENGINE-C8-001: Correlating message ${cmd.messageName} using correlation key value $correlationKey." }
      camundaClient
        .newPublishMessageCommand()
        .messageName(cmd.messageName)
        .correlationKey(correlationKey)
        .variables(cmd.payloadSupplier.get())
        .applyRestrictions(ensureSupported(cmd.restrictions))
        .send()
        .get()
      Empty
    }
  }

  override fun meta(instance: MetaInfoAware): MetaInfo {
    TODO("Not yet implemented")
  }

  override fun getSupportedRestrictions(): Set<String> = setOf(
    CommonRestrictions.TENANT_ID
  )


  private fun PublishMessageCommandStep3.applyRestrictions(restrictions: Map<String, String>): PublishMessageCommandStep3 = this.apply {
    restrictions
      .forEach { (key, value) ->
        when (key) {
          CommonRestrictions.TENANT_ID -> if (value.isNotEmpty()) { this.tenantId(value) }
        }
      }
  }

}
