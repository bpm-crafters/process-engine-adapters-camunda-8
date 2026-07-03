package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.FailJobCommandStep1
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.response.FailJobResponse
import io.camunda.client.api.worker.JobClient
import io.camunda.client.api.worker.JobHandler
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration

class SubscribingServiceTaskDeliveryTest {

  companion object {
    private const val JOB_KEY = 1234L
    private const val TASK_ID = "1234"
    private const val TOPIC = "test-topic"
    private const val ELEMENT_ID = "activity-1"
    private const val TENANT_ID = "tenant-1"
    private const val PROCESS_INSTANCE_KEY = 333L
    private const val PROCESS_DEFINITION_KEY = 444L
    private const val RETRIES = 3
  }

  // We need an instance of SubscribingServiceTaskDelivery because matches is an extension function
  // on TaskSubscriptionHandle declared inside SubscribingServiceTaskDelivery class.
  private val delivery = SubscribingServiceTaskDelivery(
    camundaClient = mock(),
    subscriptionRepository = mock(),
    workerId = "test-worker",
    retryTimeoutInSeconds = 10,
    lockDurationInSeconds = 60
  )

  @Test
  fun `should match when all restrictions match`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "test-topic",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "activity-1",
        CommonRestrictions.TENANT_ID to "tenant-1"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("test-topic")
      whenever(it.elementId).thenReturn("activity-1")
      whenever(it.tenantId).thenReturn("tenant-1")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should match and ignore workerLockDurationInMilliseconds restriction`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "test-topic",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "activity-1",
        CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS to "5000"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("test-topic")
      whenever(it.elementId).thenReturn("activity-1")
    }

    with(delivery) {
      // It should match even if workerLockDurationInMilliseconds is present in restrictions
      // but not present in ActivatedJob (it's not even a field in ActivatedJob that we check)
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should not match when task type is different`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "test-topic",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("test-topic")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should not match when topic is different`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "test-topic",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("other-topic")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should not match when restriction does not match`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "test-topic",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "activity-1"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("test-topic")
      whenever(it.elementId).thenReturn("activity-2")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should match when taskDescriptionKey is null`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = null,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("any-topic")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should terminate locally cached service task when subscribed delivery fails`() {
    val cachedTasks = linkedMapOf<String, TaskInformation>()
    var termination: TaskInformation? = null
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = TOPIC,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = TaskHandler { taskInformation, _ ->
        cachedTasks[taskInformation.taskId] = taskInformation
        error("boom")
      },
      termination = TaskTerminationHandler { taskInformation ->
        termination = taskInformation
        cachedTasks.remove(taskInformation.taskId)
      }
    )

    val subscriptionRepository = dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository().apply {
      createTaskSubscription(subscription)
    }
    val camundaClient = mock<CamundaClient>()
    val workerBuilder = mock<JobWorkerBuilderStep1>()
    val workerBuilder2 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
    val workerBuilder3 = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>()
    val jobWorker = mock<JobWorker>()
    val handler = argumentCaptor<JobHandler>()

    whenever(camundaClient.newWorker()).thenReturn(workerBuilder)
    whenever(workerBuilder.jobType(TOPIC)).thenReturn(workerBuilder2)
    whenever(workerBuilder2.handler(handler.capture())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.name(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.timeout(any<Long>())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.open()).thenReturn(jobWorker)

    val failJobStep1 = mock<FailJobCommandStep1>()
    val failJobStep2 = mock<FailJobCommandStep1.FailJobCommandStep2>()
    val failJobFuture = mock<CamundaFuture<FailJobResponse>>()
    whenever(camundaClient.newFailCommand(JOB_KEY)).thenReturn(failJobStep1)
    whenever(failJobStep1.retries(RETRIES - 1)).thenReturn(failJobStep2)
    whenever(failJobStep2.retryBackoff(Duration.ofSeconds(10))).thenReturn(failJobStep2)
    whenever(failJobStep2.send()).thenReturn(failJobFuture)

    val delivery = SubscribingServiceTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      workerId = "test-worker",
      retryTimeoutInSeconds = 10,
      lockDurationInSeconds = 60
    )

    delivery.subscribe()

    handler.firstValue.handle(mock<JobClient>(), activatedJob())

    assertThat(cachedTasks).isEmpty()
    assertThat(termination?.taskId).isEqualTo(TASK_ID)
    assertThat(termination?.meta).containsEntry(TaskInformation.REASON, TaskInformation.DELETE)
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
  }

  private fun activatedJob(): ActivatedJob =
    mock {
      whenever(it.key).thenReturn(JOB_KEY)
      whenever(it.retries).thenReturn(RETRIES)
      whenever(it.type).thenReturn(TOPIC)
      whenever(it.elementId).thenReturn(ELEMENT_ID)
      whenever(it.tenantId).thenReturn(TENANT_ID)
      whenever(it.processInstanceKey).thenReturn(PROCESS_INSTANCE_KEY)
      whenever(it.processDefinitionKey).thenReturn(PROCESS_DEFINITION_KEY)
      whenever(it.variablesAsMap).thenReturn(emptyMap())
      whenever(it.customHeaders).thenReturn(emptyMap())
    }
}
