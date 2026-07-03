package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskType
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
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
