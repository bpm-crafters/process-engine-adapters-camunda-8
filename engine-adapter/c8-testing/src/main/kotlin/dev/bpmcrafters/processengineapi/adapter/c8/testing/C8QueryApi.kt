package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.RefreshableDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.toTaskInformation
import dev.bpmcrafters.processengineapi.process.ProcessInformation
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.testing.api.ProcessTestQueryApi
import io.camunda.client.CamundaClient
import io.camunda.client.api.search.enums.UserTaskState
import java.util.function.Predicate

/**
 * Query API for C8 process-test infrastructure.
 */
internal class C8QueryApi(
  private val client: CamundaClient,
  private val delivery: RefreshableDelivery,
) : ProcessTestQueryApi {
  override fun getSupportedRestrictions() = SUPPORTED_RESTRICTIONS

  override fun findUserTasks(
    taskDescriptionKey: String?,
    restrictions: Map<String, String>,
    predicate: Predicate<TaskInformation>?,
  ): List<TaskInformation> {
    ensureSupported(restrictions)
    delivery.refresh()
    return client.newUserTaskSearchRequest()
      .filter { it.state(UserTaskState.CREATED) }
      .send()
      .join()
      .items()
      .filter { taskDescriptionKey == null || it.elementId == taskDescriptionKey || it.userTaskKey.toString() == taskDescriptionKey }
      .filter { it.matches(restrictions) }
      .map { it.toTaskInformation(null) }
      .filter { predicate?.test(it) ?: true }
  }

  override fun findExternalTasks(
    taskDescriptionKey: String?,
    restrictions: Map<String, String>,
    predicate: Predicate<TaskInformation>?,
  ): List<TaskInformation> {
    ensureSupported(restrictions)
    return client.newActivateJobsCommand()
      .jobType(taskDescriptionKey ?: throw UnsupportedOperationException("An external-task key is required by Camunda 8."))
      .maxJobsToActivate(100)
      .workerName("process-test-query")
      .send()
      .join()
      .jobs
      .filter { it.matches(restrictions) }
      .map { it.toTaskInformation() }
      .filter { predicate?.test(it) ?: true }
  }

  override fun getProcessInformation(
    instanceId: String
  ): ProcessInformation? = client.newProcessInstanceSearchRequest()
    .filter { it.processInstanceKey(instanceId.toLong()) }
    .send()
    .join()
    .singleItem()
    ?.let {
      ProcessInformation(
        it.processInstanceKey.toString(),
        mapOf(
          CommonRestrictions.PROCESS_DEFINITION_ID to it.processDefinitionKey.toString(),
          CommonRestrictions.TENANT_ID to (it.tenantId ?: ""),
        ),
      )
    }
}
