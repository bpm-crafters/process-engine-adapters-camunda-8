package dev.bpmcrafters.processengineapi.adapter.c8.testing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.bpmcrafters.processengineapi.testing.api.ProcessTestPayloadApi
import io.camunda.client.CamundaClient

/**
 * Provides access to process and task payloads for Camunda 8 process tests.
 */
internal class C8PayloadApi(private val client: CamundaClient) : ProcessTestPayloadApi {
  override fun updateProcessPayload(instanceId: String, payload: Map<String, Any?>) {
    client.newSetVariablesCommand(instanceId.toLong()).variables(payload).send().join()
  }

  override fun getProcessPayload(instanceId: String): Map<String, Any?> = variablesForProcess(instanceId)

  override fun getTaskPayload(taskId: String): Map<String, Any?> {
    val values = client.newUserTaskEffectiveVariableSearchRequest(taskId.toLong()).withFullValues().send().join().items()
    return values.associate { it.name to decode(it.value) }
  }

  private fun variablesForProcess(instanceId: String): Map<String, Any?> = client.newVariableSearchRequest()
    .filter { it.processInstanceKey(instanceId.toLong()) }
    .withFullValues()
    .send()
    .join()
    .items()
    .associate { it.name to decode(it.value) }

  private fun decode(value: String): Any? = jacksonObjectMapper().readValue(value, Any::class.java)
}
