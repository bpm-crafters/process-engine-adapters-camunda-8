package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.testing.api.ProcessTestAssertApi
import dev.bpmcrafters.processengineapi.testing.config.ProcessTestPollingConfiguration
import io.camunda.client.CamundaClient
import io.camunda.client.api.search.enums.ElementInstanceState
import io.camunda.client.api.search.enums.ProcessInstanceState
import org.awaitility.Awaitility.await
import java.util.function.Predicate

/**
 * Provides assertions for Camunda 8 process tests.
 */
internal class C8AssertApi(
  private val client: CamundaClient,
  private val query: C8QueryApi,
  private val polling: ProcessTestPollingConfiguration,
) : ProcessTestAssertApi {
  override fun getSupportedRestrictions() = SUPPORTED_RESTRICTIONS

  override fun processWaitsInUserTask(instanceId: String, taskDescriptionKey: String?, restrictions: Map<String, String>, predicate: Predicate<TaskInformation>?) =
    eventually { selected(query.findUserTasks(taskDescriptionKey, restrictions + (CommonRestrictions.PROCESS_INSTANCE_ID to instanceId), predicate)) }

  override fun processWaitsInElement(instanceId: String, elementId: String, restrictions: Map<String, String>) = eventually {
    require(
      client.newElementInstanceSearchRequest()
        .filter { it.processInstanceKey(instanceId.toLong()).elementId(elementId).state(ElementInstanceState.ACTIVE) }
        .send()
        .join()
        .items()
        .isNotEmpty(),
    ) { "Process $instanceId is not active in $elementId" }
  }

  override fun userTaskIsSelected(taskDescriptionKey: String?, restrictions: Map<String, String>, predicate: Predicate<TaskInformation>?) =
    eventually { selected(query.findUserTasks(taskDescriptionKey, restrictions, predicate)) }

  override fun externalTaskIsSelected(taskDescriptionKey: String?, restrictions: Map<String, String>, predicate: Predicate<TaskInformation>?) =
    eventually { selected(query.findExternalTasks(taskDescriptionKey, restrictions, predicate)) }

  override fun processHasPassed(instanceId: String, restrictions: Map<String, String>, vararg elementIds: String) =
    assertElements(instanceId, ordered = false, expected = true, elementIds)

  override fun processHasPassedInOrder(instanceId: String, restrictions: Map<String, String>, vararg elementIds: String) =
    assertElements(instanceId, ordered = true, expected = true, elementIds)

  override fun processHasNotPassed(instanceId: String, restrictions: Map<String, String>, vararg elementIds: String) =
    assertElements(instanceId, ordered = false, expected = false, elementIds)

  override fun processIsFinished(instanceId: String, restrictions: Map<String, String>) = eventually {
    require(
      client.newProcessInstanceSearchRequest()
        .filter { it.processInstanceKey(instanceId.toLong()).state(ProcessInstanceState.COMPLETED) }
        .send()
        .join()
        .items()
        .isNotEmpty(),
    ) { "Process $instanceId is not finished" }
  }

  override fun processHasIncidents(instanceId: String, restrictions: Map<String, String>) = eventually {
    require(client.newIncidentSearchRequest().filter { it.processInstanceKey(instanceId.toLong()) }.send().join().items().isNotEmpty()) {
      "Process $instanceId has no incidents"
    }
  }

  private fun assertElements(instanceId: String, ordered: Boolean, expected: Boolean, ids: Array<out String>) {
    require(ids.isNotEmpty()) { "At least one element id is required." }
    eventually {
      val passed = client.newElementInstanceSearchRequest()
        .filter { it.processInstanceKey(instanceId.toLong()) }
        .send()
        .join()
        .items()
        .sortedBy { it.startDate }
        .map { it.elementId }
      if (expected) {
        require(ids.all { it in passed }) { "Expected ${ids.contentToString()} to be passed; actual: $passed" }
        if (ordered) {
          ids.fold(0) { start, id ->
            passed.indexOfFirstFrom(start, id).also { require(it >= 0) { "Expected ${ids.contentToString()} in order; actual: $passed" } } + 1
          }
        }
      } else {
        require(ids.none { it in passed }) { "Expected ${ids.contentToString()} not to be passed; actual: $passed" }
      }
    }
  }

  private fun <T> eventually(block: () -> T): T =
    await().pollInterval(polling.pollInterval).atMost(polling.timeout).until(block, Predicate { true })

  private fun selected(tasks: List<TaskInformation>): TaskInformation =
    requireNotNull(tasks.singleOrNull()) { "Expected exactly one task but found ${tasks.size}: $tasks" }
}
