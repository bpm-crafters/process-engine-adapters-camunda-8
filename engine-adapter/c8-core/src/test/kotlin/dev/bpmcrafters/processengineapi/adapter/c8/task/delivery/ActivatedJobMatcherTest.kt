package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.api.response.ActivatedJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ActivatedJobMatcherTest {

  private val matcher = ActivatedJobMatcher()

  @Test
  fun `matches should return true when all real restrictions are satisfied`() {
    val job: ActivatedJob = mock()
    whenever(job.type).thenReturn("my-topic")
    whenever(job.processDefinitionKey).thenReturn(123L)

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "my-topic",
      payloadDescription = null,
      restrictions = mapOf(
        CommonRestrictions.PROCESS_DEFINITION_ID to "123",
      ),
      action = { _, _ -> },
      termination = {}
    )

    assertThat(matcher.matches(subscription, job)).isTrue()
  }

  @Test
  fun `matches should return true when workerLockDurationInMilliseconds is in restrictions`() {
    val job: ActivatedJob = mock()
    whenever(job.type).thenReturn("my-topic")
    whenever(job.processDefinitionKey).thenReturn(123L)

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "my-topic",
      payloadDescription = null,
      restrictions = mapOf(
        CommonRestrictions.PROCESS_DEFINITION_ID to "123",
        "workerLockDurationInMilliseconds" to "25000",
      ),
      action = { _, _ -> },
      termination = {}
    )

    assertThat(matcher.matches(subscription, job)).isTrue()
  }

  @Test
  fun `matches should return false when a real restriction value doesn't match`() {
    val job: ActivatedJob = mock()
    whenever(job.type).thenReturn("my-topic")
    whenever(job.processDefinitionKey).thenReturn(456L)

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "my-topic",
      payloadDescription = null,
      restrictions = mapOf(
        CommonRestrictions.PROCESS_DEFINITION_ID to "123",
      ),
      action = { _, _ -> },
      termination = {}
    )

    assertThat(matcher.matches(subscription, job)).isFalse()
  }

  @Test
  fun `matches should return false when taskType is not EXTERNAL`() {
    val job: ActivatedJob = mock()
    whenever(job.type).thenReturn("my-topic")

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "my-topic",
      payloadDescription = null,
      restrictions = mapOf(),
      action = { _, _ -> },
      termination = {}
    )

    assertThat(matcher.matches(subscription, job)).isFalse()
  }

  @Test
  fun `matches should return false when job type doesn't match taskDescriptionKey`() {
    val job: ActivatedJob = mock()
    whenever(job.type).thenReturn("other-topic")
    whenever(job.processDefinitionKey).thenReturn(123L)

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "my-topic",
      payloadDescription = null,
      restrictions = mapOf(),
      action = { _, _ -> },
      termination = {}
    )

    assertThat(matcher.matches(subscription, job)).isFalse()
  }

  @Test
  fun `matches should return true when taskDescriptionKey is null`() {
    val job: ActivatedJob = mock()
    whenever(job.type).thenReturn("any-topic")
    whenever(job.processDefinitionKey).thenReturn(123L)

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = null,
      payloadDescription = null,
      restrictions = mapOf(
        CommonRestrictions.PROCESS_DEFINITION_ID to "123",
      ),
      action = { _, _ -> },
      termination = {}
    )

    assertThat(matcher.matches(subscription, job)).isTrue()
  }

  @Test
  fun `matches should check all restriction types`() {
    val job: ActivatedJob = mock()
    whenever(job.type).thenReturn("my-topic")
    whenever(job.elementInstanceKey).thenReturn(100L)
    whenever(job.elementId).thenReturn("activity1")
    whenever(job.tenantId).thenReturn("tenant1")
    whenever(job.processInstanceKey).thenReturn(200L)
    whenever(job.processDefinitionKey).thenReturn(123L)

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "my-topic",
      payloadDescription = null,
      restrictions = mapOf(
        CommonRestrictions.EXECUTION_ID to "100",
        CommonRestrictions.ACTIVITY_ID to "activity1",
        CommonRestrictions.TENANT_ID to "tenant1",
        CommonRestrictions.PROCESS_INSTANCE_ID to "200",
        CommonRestrictions.PROCESS_DEFINITION_ID to "123",
      ),
      action = { _, _ -> },
      termination = {}
    )

    assertThat(matcher.matches(subscription, job)).isTrue()
  }

  @Test
  fun `matches should return false if any single restriction doesn't match when multiple are specified`() {
    val job: ActivatedJob = mock()
    whenever(job.type).thenReturn("my-topic")
    whenever(job.elementInstanceKey).thenReturn(100L)
    whenever(job.elementId).thenReturn("activity1")
    whenever(job.tenantId).thenReturn("tenant1")
    whenever(job.processInstanceKey).thenReturn(200L)
    whenever(job.processDefinitionKey).thenReturn(999L) // mismatch

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "my-topic",
      payloadDescription = null,
      restrictions = mapOf(
        CommonRestrictions.EXECUTION_ID to "100",
        CommonRestrictions.ACTIVITY_ID to "activity1",
        CommonRestrictions.TENANT_ID to "tenant1",
        CommonRestrictions.PROCESS_INSTANCE_ID to "200",
        CommonRestrictions.PROCESS_DEFINITION_ID to "123",
      ),
      action = { _, _ -> },
      termination = {}
    )

    assertThat(matcher.matches(subscription, job)).isFalse()
  }
}
