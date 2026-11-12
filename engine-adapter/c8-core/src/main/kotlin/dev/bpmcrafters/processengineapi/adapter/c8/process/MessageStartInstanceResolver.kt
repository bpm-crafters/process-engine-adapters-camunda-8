package dev.bpmcrafters.processengineapi.adapter.c8.process

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.adapter.c8.retry.Retryer
import dev.bpmcrafters.processengineapi.process.ProcessInformation
import io.camunda.client.CamundaClient
import io.camunda.client.api.response.PublishMessageResponse

internal class MessageStartInstanceResolver(
  private val client: CamundaClient,
  private val configuration: MessageStartInstanceResolutionConfiguration,
) {
  private val retryer = Retryer(configuration.timeout, configuration.maxTimeout)

  fun resolve(response: PublishMessageResponse): ProcessInformation {
    if (!configuration.isEnabled()) {
      return ProcessInformation(
        instanceId = "",
        meta = mapOf(CommonRestrictions.TENANT_ID to response.tenantId))
    }

    val instanceIds = retryer.execute(
      action = {
        client.newCorrelatedMessageSubscriptionSearchRequest()
        .filter { it.messageKey(response.messageKey) }
        .send()
        .get()
        .items()
        .mapNotNull { it.processInstanceKey }
        .distinct()
      },
      assertion = { ids ->
        require(ids.size == 1) {
          "Expected one process instance correlated with message publication ${response.messageKey}, but found $ids."
        }
      },
    )
    return ProcessInformation(
      instanceId = instanceIds.single().toString(),
      meta = mapOf(CommonRestrictions.TENANT_ID to response.tenantId),
    )
  }
}
