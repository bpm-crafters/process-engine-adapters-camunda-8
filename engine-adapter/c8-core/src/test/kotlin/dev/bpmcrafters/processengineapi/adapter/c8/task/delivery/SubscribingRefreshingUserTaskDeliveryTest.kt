package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskSubscription
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.command.ActivateJobsCommandStep1
import io.camunda.client.api.command.ActivateJobsCommandStep1.ActivateJobsCommandStep3
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3
import org.mockito.ArgumentMatchers.anyLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SubscribingRefreshingUserTaskDeliveryTest {

  // We need an instance of SubscribingRefreshingUserTaskDelivery because matches is an extension function
  // on TaskSubscriptionHandle declared inside SubscribingRefreshingUserTaskDelivery class.
  private val delivery = SubscribingRefreshingUserTaskDelivery(
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

    val delivery = SubscribingRefreshingUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      workerId = "test-worker",
      userTaskLockTimeoutMs = 60000L
    )

    delivery.subscribe()

    delivery.unsubscribe(subscription)
    verify(jobWorker).close()
  }
}
