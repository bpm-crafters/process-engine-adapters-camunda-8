package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskType
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.FailJobCommandStep1
import io.camunda.client.api.response.FailJobResponse
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.worker.JobClient
import io.camunda.client.api.worker.JobHandler
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3
import io.camunda.zeebe.protocol.Protocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SubscribingRefreshingZeebeJobUserTaskDeliveryTest {

  companion object {
    private const val JOB_KEY = 9876L
    private const val TASK_ID = "9876"
    private const val ELEMENT_ID = "user-task-1"
    private const val TENANT_ID = "tenant-1"
    private const val PROCESS_INSTANCE_KEY = 333L
    private const val PROCESS_DEFINITION_KEY = 444L
    private const val BPMN_PROCESS_ID = "simple-process"
    private const val RETRIES = 3
  }

  // We need an instance of SubscribingRefreshingUserTaskDelivery because matches is an extension function
  // on TaskSubscriptionHandle declared inside SubscribingRefreshingUserTaskDelivery class.
  private val delivery = SubscribingRefreshingZeebeJobUserTaskDelivery(
    camundaClient = mock(),
    subscriptionRepository = InMemSubscriptionRepository(),
    workerId = "test-worker",
    userTaskLockTimeoutMs = 60000L
  )

  @Test
  fun `should match when all restrictions match`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task-1",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "user-task-1",
        CommonRestrictions.TENANT_ID to "tenant-1"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("user-task-1")
      whenever(it.tenantId).thenReturn("tenant-1")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should match and ignore workerLockDurationInMilliseconds restriction`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task-1",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "user-task-1",
        CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS to "5000"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("user-task-1")
    }

    with(delivery) {
      // It should match even if workerLockDurationInMilliseconds is present in restrictions
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should not match when task type is different`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "user-task-1",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("user-task-1")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should not match when elementId is different`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task-1",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("other-user-task")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should not match when restriction does not match`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task-1",
      restrictions = mapOf(
        CommonRestrictions.PROCESS_DEFINITION_KEY to "process-1"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("user-task-1")
      whenever(it.bpmnProcessId).thenReturn("process-2")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should match when taskDescriptionKey is null`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = null,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("any-user-task")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should successfully subscribe and unsubscribe when taskDescriptionKey is null`() {
    val subscriptionRepository = InMemSubscriptionRepository()
    val camundaClient = mock<CamundaClient>()
    val workerBuilder = mock<JobWorkerBuilderStep1>()
    val jobWorker = mock<JobWorker>()

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = null,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    subscriptionRepository.createTaskSubscription(subscription)

    whenever(camundaClient.newWorker()).thenReturn(workerBuilder)
    val workerBuilder2 = mock<JobWorkerBuilderStep2>()
    whenever(workerBuilder.jobType(any())).thenReturn(workerBuilder2)
    val workerBuilder3 = mock<JobWorkerBuilderStep3>()
    whenever(workerBuilder2.handler(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.maxJobsActive(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.name(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.timeout(any<Long>())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.streamEnabled(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.fetchVariables(any<List<String>>())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.open()).thenReturn(jobWorker)

    val delivery = SubscribingRefreshingZeebeJobUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      workerId = "test-worker",
      userTaskLockTimeoutMs = 60000L
    )

    delivery.subscribe()

    delivery.unsubscribe(subscription)
    verify(jobWorker).close()
  }

  @Test
  fun `should terminate locally cached user task when subscribed delivery fails`() {
    val subscriptionRepository = InMemSubscriptionRepository()
    val userTaskSupport = UserTaskSupport()
    userTaskSupport.addHandler(TaskHandler { _, _ -> error("boom") })

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = ELEMENT_ID,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = userTaskSupport::onTaskDelivery,
      termination = userTaskSupport::onTaskRemoval
    )
    subscriptionRepository.createTaskSubscription(subscription)

    val camundaClient = mock<CamundaClient>()
    val workerBuilder = mock<JobWorkerBuilderStep1>()
    val workerBuilder2 = mock<JobWorkerBuilderStep2>()
    val workerBuilder3 = mock<JobWorkerBuilderStep3>()
    val jobWorker = mock<JobWorker>()
    val handler = argumentCaptor<JobHandler>()

    whenever(camundaClient.newWorker()).thenReturn(workerBuilder)
    whenever(workerBuilder.jobType(any())).thenReturn(workerBuilder2)
    whenever(workerBuilder2.handler(handler.capture())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.maxJobsActive(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.name(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.timeout(any<Long>())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.streamEnabled(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.open()).thenReturn(jobWorker)

    val failJobStep1 = mock<FailJobCommandStep1>()
    val failJobStep2 = mock<FailJobCommandStep1.FailJobCommandStep2>()
    val failJobFuture = mock<CamundaFuture<FailJobResponse>>()
    whenever(camundaClient.newFailCommand(JOB_KEY)).thenReturn(failJobStep1)
    whenever(failJobStep1.retries(RETRIES)).thenReturn(failJobStep2)
    whenever(failJobStep2.send()).thenReturn(failJobFuture)

    val delivery = SubscribingRefreshingZeebeJobUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      workerId = "test-worker",
      userTaskLockTimeoutMs = 60000L
    )

    delivery.subscribe()

    handler.firstValue.handle(mock<JobClient>(), activatedJob())

    assertThat(userTaskSupport.exists(TASK_ID)).isFalse()
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
  }

  private fun activatedJob(): ActivatedJob =
    mock {
      whenever(it.key).thenReturn(JOB_KEY)
      whenever(it.retries).thenReturn(RETRIES)
      whenever(it.type).thenReturn(Protocol.USER_TASK_JOB_TYPE)
      whenever(it.elementId).thenReturn(ELEMENT_ID)
      whenever(it.tenantId).thenReturn(TENANT_ID)
      whenever(it.bpmnProcessId).thenReturn(BPMN_PROCESS_ID)
      whenever(it.processDefinitionKey).thenReturn(PROCESS_DEFINITION_KEY)
      whenever(it.processInstanceKey).thenReturn(PROCESS_INSTANCE_KEY)
      whenever(it.variablesAsMap).thenReturn(emptyMap())
      whenever(it.customHeaders).thenReturn(emptyMap())
    }
}
