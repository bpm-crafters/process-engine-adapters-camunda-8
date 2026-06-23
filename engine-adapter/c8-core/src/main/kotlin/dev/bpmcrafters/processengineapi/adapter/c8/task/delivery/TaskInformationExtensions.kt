package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.task.TaskInformation
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.search.enums.ListenerEventType
import io.camunda.client.api.search.response.Form
import io.camunda.client.api.search.response.UserTask
import io.camunda.zeebe.protocol.Protocol

fun ActivatedJob.toTaskInformation(): TaskInformation = TaskInformation(
  taskId = "${this.key}",
  meta = metaOf(
    CommonRestrictions.TENANT_ID to this.tenantId,
    CommonRestrictions.ACTIVITY_ID to this.elementId,
    CommonRestrictions.PROCESS_DEFINITION_KEY to this.bpmnProcessId,
    CommonRestrictions.PROCESS_DEFINITION_ID to "${this.processDefinitionKey}",
    CommonRestrictions.PROCESS_INSTANCE_ID to "${this.processInstanceKey}",
    "formKey" to this.customHeaders.getOrDefault(Protocol.USER_TASK_FORM_KEY_HEADER_NAME, null),
    "assignee" to this.customHeaders.getOrDefault(Protocol.USER_TASK_ASSIGNEE_HEADER_NAME, null),
    "dueDate" to this.customHeaders.getOrDefault(Protocol.USER_TASK_DUE_DATE_HEADER_NAME, null),
    "candidateUsers" to this.customHeaders.getOrDefault(Protocol.USER_TASK_CANDIDATE_USERS_HEADER_NAME, null),
    "candidateGroups" to this.customHeaders.getOrDefault(Protocol.USER_TASK_CANDIDATE_GROUPS_HEADER_NAME, null),
    "followUpDate" to this.customHeaders.getOrDefault(Protocol.USER_TASK_FOLLOW_UP_DATE_HEADER_NAME, null),
    "topicName" to this.type,
    TaskInformation.RETRIES to this.retries.toString(),
  )
)

fun UserTask.toTaskInformation(form: Form?): TaskInformation = TaskInformation(
  taskId = this.userTaskKey.toString(),
  meta = metaOf(
    CommonRestrictions.ACTIVITY_ID to this.elementId,
    CommonRestrictions.PROCESS_DEFINITION_KEY to this.bpmnProcessId,
    CommonRestrictions.PROCESS_DEFINITION_ID to this.processDefinitionKey.toString(),
    CommonRestrictions.PROCESS_INSTANCE_ID to this.processInstanceKey.toString(),
    CommonRestrictions.TENANT_ID to this.tenantId,
    "assignee" to this.assignee,
    "candidateUsers" to this.candidateUsers?.joinToString(","),
    "candidateGroups" to this.candidateGroups?.joinToString(","),
    "followUpDate" to this.followUpDate?.toString(),
    "dueDate" to this.dueDate?.toString(),
    "creationDate" to this.creationDate.toString(),
    "processName" to this.processName,
    "taskName" to this.name,
    "formId" to form?.formKey.toString(),
    "formKey" to form?.formId,
    "formVersion" to form?.let { "${it.version}" },
    "taskState" to this.state.name,
  )
)

fun ActivatedJob.toUserTaskListenerEvent(): Pair<String, ListenerEventType> {
  val userTask = requireNotNull(this.userTask) {
    "Activated job ${this.key} is not a user task listener job."
  }
  val userTaskKey = requireNotNull(userTask.userTaskKey) {
    "Activated user task listener job ${this.key} does not contain a user task key."
  }
  return Pair(
    userTaskKey.toString(),
    this.listenerEventType
  )
}

fun ActivatedJob.toUserTaskListenerTaskInformation(): TaskInformation {
  val userTask = requireNotNull(this.userTask) {
    "Activated job ${this.key} is not a user task listener job."
  }
  return TaskInformation(
    taskId = requireNotNull(userTask.userTaskKey) {
      "Activated user task listener job ${this.key} does not contain a user task key."
    }.toString(),
    meta = metaOf(
      CommonRestrictions.TENANT_ID to this.tenantId,
      CommonRestrictions.ACTIVITY_ID to this.elementId,
      CommonRestrictions.EXECUTION_ID to "${this.elementInstanceKey}",
      CommonRestrictions.PROCESS_DEFINITION_KEY to this.bpmnProcessId,
      CommonRestrictions.PROCESS_DEFINITION_ID to "${this.processDefinitionKey}",
      CommonRestrictions.PROCESS_INSTANCE_ID to "${this.processInstanceKey}",
      "eventType" to this.listenerEventType.name,
      "action" to userTask.action,
      "assignee" to userTask.assignee,
      "candidateUsers" to userTask.candidateUsers?.joinToString(","),
      "candidateGroups" to userTask.candidateGroups?.joinToString(","),
      "changedAttributes" to userTask.changedAttributes?.joinToString(","),
      "dueDate" to userTask.dueDate?.toString(),
      "followUpDate" to userTask.followUpDate?.toString(),
      "formKey" to userTask.formKey?.toString(),
      "priority" to userTask.priority?.toString(),
      "topicName" to this.type,
      TaskInformation.RETRIES to this.retries.toString(),
    )
  )
}

/**
 * Creates a map of the provided pairs.
 *
 * If the 2nd component of a pair is `null`, the pair is dropped and not added to the resulting map.
 */
fun metaOf(vararg pairs: Pair<String, String?>): Map<String, String> =
  sequenceOf(*pairs)
    .filter { it.second != null }
    .associate {
      @Suppress("UNCHECKED_CAST")
      it as Pair<String, String>
    }
