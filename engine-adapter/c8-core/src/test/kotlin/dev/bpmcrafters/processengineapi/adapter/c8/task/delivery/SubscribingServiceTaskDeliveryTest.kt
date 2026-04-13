package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.api.response.ActivatedJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SubscribingServiceTaskDeliveryTest {

  private val matcher = ActivatedJobMatcher()

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

    assertThat(matcher.matches(subscription, job)).isTrue()
  }

  @Test
  fun `should match and ignore workerLockDurationInMilliseconds restriction`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "test-topic",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "activity-1",
        "workerLockDurationInMilliseconds" to "5000"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.type).thenReturn("test-topic")
      whenever(it.elementId).thenReturn("activity-1")
    }

    // It should match even if workerLockDurationInMilliseconds is present in restrictions
    // but not present in ActivatedJob (it's not even a field in ActivatedJob that we check)
    assertThat(matcher.matches(subscription, job)).isTrue()
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

    assertThat(matcher.matches(subscription, job)).isFalse()
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

    assertThat(matcher.matches(subscription, job)).isFalse()
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

    assertThat(matcher.matches(subscription, job)).isFalse()
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

    assertThat(matcher.matches(subscription, job)).isTrue()
  }
}
