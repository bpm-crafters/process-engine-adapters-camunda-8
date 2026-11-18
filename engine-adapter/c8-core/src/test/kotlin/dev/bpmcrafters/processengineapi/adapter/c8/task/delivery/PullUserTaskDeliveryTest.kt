package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskType
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.fetch.VariableGetRequest
import io.camunda.client.api.search.enums.UserTaskState
import io.camunda.client.api.search.request.UserTaskSearchRequest
import io.camunda.client.api.search.request.UserTaskVariableSearchRequest
import io.camunda.client.api.search.response.SearchResponse
import io.camunda.client.api.search.response.UserTask
import io.camunda.client.api.search.response.Variable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.util.function.Consumer

class PullUserTaskDeliveryTest {

  companion object {
    private const val TASK_ID = "1234"
    private const val ELEMENT_ID = "user-task"
    private const val BPMN_PROCESS_ID = "simple-process"
    private const val PROCESS_DEFINITION_KEY = 444L
    private const val PROCESS_INSTANCE_KEY = 333L
    private const val TENANT_ID = "tenant-1"
  }

  @Test
  fun `should terminate locally cached user task when pull delivery fails`() {
    val camundaClient = mock<CamundaClient>()
    val subscriptionRepository = InMemSubscriptionRepository()
    val userTaskSupport = UserTaskSupport()
    userTaskSupport.addHandler(TaskHandler { _, _ -> error("boom") })

    subscriptionRepository.createTaskSubscription(
      TaskSubscriptionHandle(
        taskType = TaskType.USER,
        taskDescriptionKey = ELEMENT_ID,
        restrictions = emptyMap(),
        payloadDescription = null,
        action = userTaskSupport::onTaskDelivery,
        termination = userTaskSupport::onTaskRemoval
      )
    )
    val task = userTask()

    val searchRequest = mock<UserTaskSearchRequest>()
    val searchFuture = mock<CamundaFuture<SearchResponse<UserTask>>>()
    val searchResponse = mock<SearchResponse<UserTask>>()
    whenever(camundaClient.newUserTaskSearchRequest()).thenReturn(searchRequest)
    whenever(searchRequest.filter(any<Consumer<io.camunda.client.api.search.filter.UserTaskFilter>>())).thenReturn(searchRequest)
    whenever(searchRequest.send()).thenReturn(searchFuture)
    whenever(searchFuture.join()).thenReturn(searchResponse)
    whenever(searchResponse.items()).thenReturn(listOf(task), emptyList())

    val variableSearchRequest = mock<UserTaskVariableSearchRequest>()
    val variableSearchFuture = mock<CamundaFuture<SearchResponse<Variable>>>()
    val variableSearchResponse = mock<SearchResponse<Variable>>()
    whenever(camundaClient.newUserTaskVariableSearchRequest(TASK_ID.toLong())).thenReturn(variableSearchRequest)
    whenever(variableSearchRequest.withFullValues()).thenReturn(variableSearchRequest)
    whenever(variableSearchRequest.send()).thenReturn(variableSearchFuture)
    whenever(variableSearchFuture.join()).thenReturn(variableSearchResponse)
    whenever(variableSearchResponse.items()).thenReturn(emptyList())

    val delivery = PullUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository
    )

    delivery.refresh()

    assertThat(userTaskSupport.exists(TASK_ID)).isFalse()
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()

    delivery.refresh()

    assertThat(userTaskSupport.getAllTasks()).isEmpty()
  }

  @Test
  fun `should use ordinary variable search and deliver typed values without fetching untruncated variables`() {
    val fixture = Fixture()
    val expected = linkedMapOf<String, Any?>(
      "text" to "complete value",
      "number" to 42,
      "flag" to true,
      "nothing" to null,
      "items" to listOf("first", 2, false),
      "object" to mapOf("nested" to listOf(1, 2))
    )
    val mapper = jacksonObjectMapper()
    fixture.variables(expected.entries.mapIndexed { index, (name, value) ->
      variable(name, mapper.writeValueAsString(value), if (index % 2 == 0) false else null)
    })

    fixture.delivery.refresh()

    assertThat(fixture.payloads).containsExactly(expected)
    verify(fixture.variableSearchRequest).send()
    verify(fixture.variableSearchRequest, never()).withFullValues()
    verify(fixture.camundaClient, never()).newVariableGetRequest(any<Long>())
  }

  @Test
  fun `should fetch truncated strings and objects by variable key before delivering full typed payload`() {
    val fixture = Fixture()
    val text = "long value with quotes \" and newline\n".repeat(400)
    val details = mapOf("text" to text, "items" to listOf(1, true, null, mapOf("nested" to "value")))
    val mapper = jacksonObjectMapper()
    val fullText = mapper.writeValueAsString(text)
    val fullDetails = mapper.writeValueAsString(details)
    assertThat(fullText.length).isGreaterThan(8192)
    assertThat(fullDetails.length).isGreaterThan(8192)
    fixture.variables(listOf(
      variable("text", "\"valid but incomplete prefix\"", true, 501L),
      variable("details", "{\"text\":", true, 502L),
      variable("count", "7", false)
    ))
    fixture.fullVariable(501L, fullText)
    fixture.fullVariable(502L, fullDetails)

    fixture.delivery.refresh()

    assertThat(fixture.payloads).containsExactly(mapOf("text" to text, "details" to details, "count" to 7))
    verify(fixture.camundaClient).newVariableGetRequest(501L)
    verify(fixture.camundaClient).newVariableGetRequest(502L)
    verify(fixture.camundaClient, never()).newVariableGetRequest(0L)
  }

  @Test
  fun `should terminate previously cached task without partial delivery when full variable fetch fails`() {
    val fixture = Fixture()
    fixture.variables(listOf(variable("text", "\"initial\"", false)))
    fixture.delivery.refresh()
    assertThat(fixture.userTaskSupport.exists(TASK_ID)).isTrue()
    assertThat(fixture.subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNotNull()
    fixture.variables(listOf(
      variable("count", "7", false),
      variable("text", "\"incomplete prefix\"", true, 501L)
    ))
    val future = fixture.fullVariable(501L, "\"unused\"")
    whenever(future.join()).thenThrow(IllegalStateException("variable fetch failed"))

    fixture.delivery.refresh()

    assertThat(fixture.payloads).containsExactly(mapOf("text" to "initial"))
    assertThat(fixture.userTaskSupport.exists(TASK_ID)).isFalse()
    assertThat(fixture.subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
    assertThat(fixture.terminations).hasSize(1)
    assertThat(fixture.terminations.single().taskId).isEqualTo(TASK_ID)
    assertThat(fixture.terminations.single().meta).containsEntry(TaskInformation.REASON, TaskInformation.DELETE)
    verify(fixture.camundaClient).newVariableGetRequest(501L)
  }

  @Test
  fun `should fail closed without fetching a default key when truncated variable key is missing`() {
    val fixture = Fixture()
    fixture.variables(listOf(variable("text", "\"initial\"", false)))
    fixture.delivery.refresh()
    assertThat(fixture.userTaskSupport.exists(TASK_ID)).isTrue()
    fixture.variables(listOf(variable("text", "\"incomplete prefix\"", true, null)))

    fixture.delivery.refresh()

    assertThat(fixture.payloads).containsExactly(mapOf("text" to "initial"))
    assertThat(fixture.userTaskSupport.exists(TASK_ID)).isFalse()
    assertThat(fixture.subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
    assertThat(fixture.terminations).hasSize(1)
    assertThat(fixture.terminations.single().meta).containsEntry(TaskInformation.REASON, TaskInformation.DELETE)
    verify(fixture.camundaClient, never()).newVariableGetRequest(any<Long>())
  }

  private inner class Fixture {
    val camundaClient = mock<CamundaClient>()
    val subscriptionRepository = InMemSubscriptionRepository()
    val userTaskSupport = UserTaskSupport()
    val payloads = mutableListOf<Map<String, Any?>>()
    val terminations = mutableListOf<TaskInformation>()
    val variableSearchRequest = mock<UserTaskVariableSearchRequest>()
    private val variableSearchResponse = mock<SearchResponse<Variable>>()
    val delivery = PullUserTaskDelivery(camundaClient, subscriptionRepository)

    init {
      subscriptionRepository.createTaskSubscription(TaskSubscriptionHandle(
        taskType = TaskType.USER,
        taskDescriptionKey = ELEMENT_ID,
        restrictions = emptyMap(),
        payloadDescription = null,
        action = { information, variables ->
          userTaskSupport.onTaskDelivery(information, variables)
          payloads.add(variables)
        },
        termination = { information ->
          terminations.add(information)
          userTaskSupport.onTaskRemoval(information)
        }
      ))
      val searchRequest = mock<UserTaskSearchRequest>()
      val searchFuture = mock<CamundaFuture<SearchResponse<UserTask>>>()
      val searchResponse = mock<SearchResponse<UserTask>>()
      val task = userTask()
      whenever(camundaClient.newUserTaskSearchRequest()).thenReturn(searchRequest)
      whenever(searchRequest.filter(any<Consumer<io.camunda.client.api.search.filter.UserTaskFilter>>())).thenReturn(searchRequest)
      whenever(searchRequest.send()).thenReturn(searchFuture)
      whenever(searchFuture.join()).thenReturn(searchResponse)
      whenever(searchResponse.items()).thenReturn(listOf(task))

      val variableSearchFuture = mock<CamundaFuture<SearchResponse<Variable>>>()
      whenever(camundaClient.newUserTaskVariableSearchRequest(TASK_ID.toLong())).thenReturn(variableSearchRequest)
      // Keep the current request executable so regression failures expose behavior, not an unstubbed fluent call.
      whenever(variableSearchRequest.withFullValues()).thenReturn(variableSearchRequest)
      whenever(variableSearchRequest.send()).thenReturn(variableSearchFuture)
      whenever(variableSearchFuture.join()).thenReturn(variableSearchResponse)
    }

    fun variables(values: List<Variable>) {
      whenever(variableSearchResponse.items()).thenReturn(values)
    }

    fun fullVariable(key: Long, value: String): CamundaFuture<Variable> {
      val request = mock<VariableGetRequest>()
      val future = mock<CamundaFuture<Variable>>()
      val fullValue = variable("unused", value, false, key)
      whenever(camundaClient.newVariableGetRequest(key)).thenReturn(request)
      whenever(request.send()).thenReturn(future)
      whenever(future.join()).thenReturn(fullValue)
      return future
    }
  }

  private fun variable(name: String, value: String, truncated: Boolean?, key: Long? = null): Variable =
    mock {
      whenever(it.name).thenReturn(name)
      whenever(it.value).thenReturn(value)
      whenever(it.isTruncated).thenReturn(truncated)
      whenever(it.variableKey).thenReturn(key)
    }

  private fun userTask(): UserTask =
    mock {
      whenever(it.userTaskKey).thenReturn(TASK_ID.toLong())
      whenever(it.name).thenReturn("Approve request")
      whenever(it.state).thenReturn(UserTaskState.CREATED)
      whenever(it.assignee).thenReturn("kermit")
      whenever(it.elementId).thenReturn(ELEMENT_ID)
      whenever(it.candidateGroups).thenReturn(emptyList())
      whenever(it.candidateUsers).thenReturn(emptyList())
      whenever(it.bpmnProcessId).thenReturn(BPMN_PROCESS_ID)
      whenever(it.processName).thenReturn("Simple process")
      whenever(it.processDefinitionKey).thenReturn(PROCESS_DEFINITION_KEY)
      whenever(it.processInstanceKey).thenReturn(PROCESS_INSTANCE_KEY)
      whenever(it.formKey).thenReturn(null)
      whenever(it.creationDate).thenReturn(OffsetDateTime.parse("2026-07-03T10:15:30+02:00"))
      whenever(it.followUpDate).thenReturn(null)
      whenever(it.dueDate).thenReturn(null)
      whenever(it.tenantId).thenReturn(TENANT_ID)
    }
}
