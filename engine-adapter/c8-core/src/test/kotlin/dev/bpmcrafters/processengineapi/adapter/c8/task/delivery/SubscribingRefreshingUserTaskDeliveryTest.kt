package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.api.response.ActivatedJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SubscribingRefreshingUserTaskDeliveryTest {

  // We need an instance of SubscribingRefreshingUserTaskDelivery because matches is an extension function
  // on TaskSubscriptionHandle declared inside SubscribingRefreshingUserTaskDelivery class.
  private val delivery = SubscribingRefreshingUserTaskDelivery(
    camundaClient = mock(),
    subscriptionRepository = mock(),
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
        "workerLockDurationInMilliseconds" to "5000"
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
}
