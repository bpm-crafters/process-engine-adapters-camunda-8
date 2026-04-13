package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.api.response.ActivatedJob

/**
 * Determines whether a [TaskSubscriptionHandle] should receive a given [ActivatedJob]
 * by checking task type, job type, and all restriction entries.
 *
 * Note: when adding a new restriction key, add a corresponding when-case in
 * [jobMatchesRestriction]; unhandled keys fall through to `else -> false`
 * and silently prevent task delivery.
 */
class ActivatedJobMatcher {

  private val keysToIgnore = setOf(
    "workerLockDurationInMilliseconds",
  )

  fun matches(subscription: TaskSubscriptionHandle, job: ActivatedJob): Boolean {
    return subscription.taskType == TaskType.EXTERNAL
      && (subscription.taskDescriptionKey == null || subscription.taskDescriptionKey == job.type)
      && subscription.restrictions.all { jobMatchesRestriction(job, it) }
  }

  private fun jobMatchesRestriction(
    job: ActivatedJob,
    restriction: Map.Entry<String, String>,
  ): Boolean {
    val (key, value) = restriction
    if (keysToIgnore.contains(key)) return true
    return when (key) {
      CommonRestrictions.EXECUTION_ID -> value == "${job.elementInstanceKey}"
      CommonRestrictions.ACTIVITY_ID -> value == job.elementId
      CommonRestrictions.TENANT_ID -> value == job.tenantId
      CommonRestrictions.PROCESS_INSTANCE_ID -> value == "${job.processInstanceKey}"
      CommonRestrictions.PROCESS_DEFINITION_ID -> value == "${job.processDefinitionKey}"
      else -> false
    }
  }
}
