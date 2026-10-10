package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.CommonRestrictions
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.search.response.UserTask

internal val SUPPORTED_RESTRICTIONS = setOf(
  CommonRestrictions.PROCESS_INSTANCE_ID,
  CommonRestrictions.PROCESS_DEFINITION_ID,
  CommonRestrictions.PROCESS_DEFINITION_KEY,
  CommonRestrictions.ACTIVITY_ID,
  CommonRestrictions.TENANT_ID,
)

internal fun UserTask.matches(restrictions: Map<String, String>): Boolean = restrictions.all { (key, value) ->
  when (key) {
    CommonRestrictions.PROCESS_INSTANCE_ID -> processInstanceKey.toString() == value
    CommonRestrictions.PROCESS_DEFINITION_ID -> processDefinitionKey.toString() == value
    CommonRestrictions.PROCESS_DEFINITION_KEY -> bpmnProcessId == value
    CommonRestrictions.ACTIVITY_ID -> elementId == value
    CommonRestrictions.TENANT_ID -> tenantId == value
    else -> false
  }
}

internal fun ActivatedJob.matches(restrictions: Map<String, String>): Boolean = restrictions.all { (key, value) ->
  when (key) {
    CommonRestrictions.PROCESS_INSTANCE_ID -> processInstanceKey.toString() == value
    CommonRestrictions.PROCESS_DEFINITION_ID -> processDefinitionKey.toString() == value
    CommonRestrictions.PROCESS_DEFINITION_KEY -> bpmnProcessId == value
    CommonRestrictions.ACTIVITY_ID -> elementId == value
    CommonRestrictions.TENANT_ID -> tenantId == value
    else -> false
  }
}

internal fun List<String>.indexOfFirstFrom(start: Int, value: String): Int = subList(start, size)
  .indexOf(value)
  .let { if (it < 0) -1 else start + it }
