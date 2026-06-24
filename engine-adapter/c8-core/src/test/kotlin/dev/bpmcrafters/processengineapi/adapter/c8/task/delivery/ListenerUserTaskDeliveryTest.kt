package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.CompleteJobCommandStep1
import io.camunda.client.api.command.FailJobCommandStep1
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.response.CompleteJobResponse
import io.camunda.client.api.response.FailJobResponse
import io.camunda.client.api.response.UserTaskProperties
import io.camunda.client.api.search.enums.JobKind
import io.camunda.client.api.search.enums.ListenerEventType
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

class ListenerUserTaskDeliveryTest {

  companion object {
    const val JOB_KEY = 9876L
    const val TASK_ID = "1234"
    const val ELEMENT_ID = "user-task"
    const val ELEMENT_INSTANCE_KEY = 222L
    const val PROCESS_INSTANCE_KEY = 333L
    const val PROCESS_DEFINITION_KEY = 444L
    const val BPMN_PROCESS_ID = "simple-process"
    const val TENANT_ID = "tenant-1"
  }


  private val camundaClient: CamundaClient = mock()
  private val subscriptionRepository = InMemSubscriptionRepository()
  private val delivery = ListenerUserTaskDelivery(
    camundaClient = camundaClient,
    subscriptionRepository = subscriptionRepository,
    retryTimeoutInSeconds = 7
  )

  @Test
  fun `should open listener worker for configured topic`() {
    val workerBuilder = mock<JobWorkerBuilderStep1>()
    val workerBuilder2 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
    val workerBuilder3 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>()
    val jobWorker = mock<JobWorker>()

    subscriptionRepository.createTaskSubscription(subscription(payloadDescription = setOf("keep")))
    whenever(camundaClient.newWorker()).thenReturn(workerBuilder)
    whenever(workerBuilder.jobType("process-engine-user-tasks")).thenReturn(workerBuilder2)
    whenever(workerBuilder2.handler(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.name("process-engine-user-tasks-worker")).thenReturn(workerBuilder3)
    whenever(workerBuilder3.maxJobsActive(32)).thenReturn(workerBuilder3)
    whenever(workerBuilder3.streamEnabled(true)).thenReturn(workerBuilder3)
    whenever(workerBuilder3.timeout(300_000L)).thenReturn(workerBuilder3)
    whenever(workerBuilder3.fetchVariables(listOf("keep"))).thenReturn(workerBuilder3)
    whenever(workerBuilder3.open()).thenReturn(jobWorker)

    delivery.subscribe()

    verify(workerBuilder).jobType("process-engine-user-tasks")
    verify(workerBuilder3).fetchVariables(listOf("keep"))
    verify(workerBuilder3).open()
  }

  @Test
  fun `should deliver creating event and activate subscription`() {
    var taskInformation: TaskInformation? = null
    var payload: Map<String, Any?>? = null
    val subscription = subscription(
      payloadDescription = setOf("keep"),
      action = { info, variables ->
        taskInformation = info
        payload = variables
      }
    )
    subscriptionRepository.createTaskSubscription(subscription)
    expectComplete()

    delivery.consumeActivatedJob(
      listenerJob(
        eventType = ListenerEventType.CREATING,
        variables = mapOf("keep" to "value", "drop" to "ignored")
      )
    )

    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isEqualTo(subscription)
    assertThat(taskInformation?.taskId).isEqualTo(TASK_ID)
    assertThat(taskInformation?.meta).containsEntry(TaskInformation.REASON, TaskInformation.CREATE)
    assertThat(payload).containsEntry("keep", "value")
    assertThat(payload).doesNotContainKey("drop")
  }

  @Test
  fun `should recover update as create when task was not active locally`() {
    var taskInformation: TaskInformation? = null
    subscriptionRepository.createTaskSubscription(
      subscription(action = { info, _ -> taskInformation = info })
    )
    expectComplete()

    delivery.consumeActivatedJob(listenerJob(eventType = ListenerEventType.UPDATING))

    assertThat(taskInformation?.meta).containsEntry(TaskInformation.REASON, TaskInformation.CREATE)
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNotNull
  }

  @Test
  fun `should deliver assignment when task was already active locally`() {
    var taskInformation: TaskInformation? = null
    val subscription = subscription(action = { info, _ -> taskInformation = info })
    subscriptionRepository.createTaskSubscription(subscription)
    subscriptionRepository.activateSubscriptionForTask(TASK_ID, subscription)
    expectComplete()

    delivery.consumeActivatedJob(listenerJob(eventType = ListenerEventType.ASSIGNING))

    assertThat(taskInformation?.meta).containsEntry(TaskInformation.REASON, TaskInformation.ASSIGN)
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isEqualTo(subscription)
  }

  @Test
  fun `should deliver update when task was already active locally`() {
    var taskInformation: TaskInformation? = null
    val subscription = subscription(action = { info, _ -> taskInformation = info })
    subscriptionRepository.createTaskSubscription(subscription)
    subscriptionRepository.activateSubscriptionForTask(TASK_ID, subscription)
    expectComplete()

    delivery.consumeActivatedJob(listenerJob(eventType = ListenerEventType.UPDATING))

    assertThat(taskInformation?.meta).containsEntry(TaskInformation.REASON, TaskInformation.UPDATE)
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isEqualTo(subscription)
  }

  @Test
  fun `should terminate completed task and deactivate subscription`() {
    var termination: TaskInformation? = null
    val subscription = subscription(termination = { termination = it })
    subscriptionRepository.createTaskSubscription(subscription)
    subscriptionRepository.activateSubscriptionForTask(TASK_ID, subscription)
    expectComplete()

    delivery.consumeActivatedJob(listenerJob(eventType = ListenerEventType.COMPLETING))

    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
    assertThat(termination?.meta).containsEntry(TaskInformation.REASON, TaskInformation.COMPLETE)
  }

  @Test
  fun `should terminate canceled task and deactivate subscription`() {
    var termination: TaskInformation? = null
    val subscription = subscription(termination = { termination = it })
    subscriptionRepository.createTaskSubscription(subscription)
    subscriptionRepository.activateSubscriptionForTask(TASK_ID, subscription)
    expectComplete()

    delivery.consumeActivatedJob(listenerJob(eventType = ListenerEventType.CANCELING))

    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
    assertThat(termination?.meta).containsEntry(TaskInformation.REASON, TaskInformation.DELETE)
  }

  @Test
  fun `should fail listener job when subscriber action throws`() {
    subscriptionRepository.createTaskSubscription(subscription(action = { _, _ -> error("boom") }))
    val failStep2 = expectFail()

    delivery.consumeActivatedJob(listenerJob(eventType = ListenerEventType.CREATING, retries = 3))

    verify(failStep2).retryBackoff(Duration.ofSeconds(7))
    verify(failStep2).errorMessage("boom")
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
  }

  @Test
  fun `should complete listener job when no subscription matches`() {
    subscriptionRepository.createTaskSubscription(
      subscription(restrictions = mapOf(CommonRestrictions.ACTIVITY_ID to "other-task"))
    )
    val completeCommand = expectComplete()

    delivery.consumeActivatedJob(listenerJob(eventType = ListenerEventType.CREATING))

    verify(completeCommand).send()
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
  }

  @Test
  fun `should match user task listener restrictions`() {
    val subscription = subscription(
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to ELEMENT_ID,
        CommonRestrictions.EXECUTION_ID to ELEMENT_INSTANCE_KEY.toString(),
        CommonRestrictions.TENANT_ID to TENANT_ID,
        CommonRestrictions.PROCESS_INSTANCE_ID to PROCESS_INSTANCE_KEY.toString(),
        CommonRestrictions.PROCESS_DEFINITION_ID to PROCESS_DEFINITION_KEY.toString(),
        CommonRestrictions.PROCESS_DEFINITION_KEY to BPMN_PROCESS_ID,
        CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS to "5000",
      )
    )

    with(delivery) {
      assertThat(subscription.matches(listenerJob())).isTrue()
    }
  }

  @Test
  fun `should not match non listener job`() {
    val subscription = subscription()
    val job = listenerJob(kind = JobKind.BPMN_ELEMENT)

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  private fun expectComplete(jobKey: Long = JOB_KEY): CompleteJobCommandStep1 {
    val command = mock<CompleteJobCommandStep1>()
    val future = mock<CamundaFuture<CompleteJobResponse>>()
    whenever(camundaClient.newCompleteCommand(jobKey)).thenReturn(command)
    whenever(command.send()).thenReturn(future)
    return command
  }

  private fun expectFail(jobKey: Long = JOB_KEY): FailJobCommandStep1.FailJobCommandStep2 {
    val step1 = mock<FailJobCommandStep1>()
    val step2 = mock<FailJobCommandStep1.FailJobCommandStep2>()
    val future = mock<CamundaFuture<FailJobResponse>>()
    whenever(camundaClient.newFailCommand(jobKey)).thenReturn(step1)
    whenever(step1.retries(2)).thenReturn(step2)
    whenever(step2.retryBackoff(any())).thenReturn(step2)
    whenever(step2.errorMessage(any())).thenReturn(step2)
    whenever(step2.send()).thenReturn(future)
    return step2
  }

  private fun subscription(
    taskDescriptionKey: String? = ELEMENT_ID,
    payloadDescription: Set<String>? = null,
    restrictions: Map<String, String> = emptyMap(),
    action: (TaskInformation, Map<String, Any?>) -> Unit = { _, _ -> },
    termination: (TaskInformation) -> Unit = {}
  ) = TaskSubscriptionHandle(
    taskType = TaskType.USER,
    taskDescriptionKey = taskDescriptionKey,
    restrictions = restrictions,
    payloadDescription = payloadDescription,
    action = action,
    termination = termination
  )

  private fun listenerJob(
    kind: JobKind = JobKind.TASK_LISTENER,
    eventType: ListenerEventType = ListenerEventType.CREATING,
    taskId: String = TASK_ID,
    retries: Int = 3,
    variables: Map<String, Any> = emptyMap()
  ): ActivatedJob {
    val userTask = mock<UserTaskProperties> {
      whenever(it.userTaskKey).thenReturn(taskId.toLong())
      whenever(it.action).thenReturn(eventType.name.lowercase())
      whenever(it.assignee).thenReturn("kermit")
      whenever(it.candidateUsers).thenReturn(listOf("gonzo", "fozzy"))
      whenever(it.candidateGroups).thenReturn(listOf("avengers"))
      whenever(it.changedAttributes).thenReturn(listOf("assignee"))
      whenever(it.priority).thenReturn(50)
    }
    return mock {
      whenever(it.kind).thenReturn(kind)
      whenever(it.key).thenReturn(JOB_KEY)
      whenever(it.type).thenReturn("process-engine-user-tasks")
      whenever(it.listenerEventType).thenReturn(eventType)
      whenever(it.userTask).thenReturn(userTask)
      whenever(it.tenantId).thenReturn(TENANT_ID)
      whenever(it.elementId).thenReturn(ELEMENT_ID)
      whenever(it.elementInstanceKey).thenReturn(ELEMENT_INSTANCE_KEY)
      whenever(it.bpmnProcessId).thenReturn(BPMN_PROCESS_ID)
      whenever(it.processDefinitionKey).thenReturn(PROCESS_DEFINITION_KEY)
      whenever(it.processInstanceKey).thenReturn(PROCESS_INSTANCE_KEY)
      whenever(it.retries).thenReturn(retries)
      whenever(it.variablesAsMap).thenReturn(variables)
    }
  }

}
