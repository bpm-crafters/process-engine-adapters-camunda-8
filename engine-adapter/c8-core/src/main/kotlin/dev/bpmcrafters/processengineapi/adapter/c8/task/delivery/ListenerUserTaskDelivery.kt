package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.impl.task.filterBySubscription
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskSubscription
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.search.enums.JobKind
import io.camunda.client.api.search.enums.ListenerEventType
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Delivery based ona user task listener.
 */
class UserTaskListenerDelivery(
  private val camundaClient: CamundaClient,
  private val subscriptionRepository: SubscriptionRepository,
  private val topic: String = DEFAULT_TOPIC,
  private val workerId: String = DEFAULT_WORKER_ID,
  private val maxJobsActive: Int = DEFAULT_MAX_JOBS_ACTIVE,
  private val streamEnabled: Boolean = true,
  private val lockTimeInSeconds: Long = DEFAULT_LOCK_TIME_IN_SECONDS,
  private val retryTimeoutInSeconds: Long = DEFAULT_RETRY_TIMEOUT_IN_SECONDS
) : SubscribingUserTaskDelivery, AutoCloseable {

  companion object {
    const val DEFAULT_TOPIC = "process-engine-user-tasks"
    const val DEFAULT_WORKER_ID = "process-engine-user-tasks-worker"
    const val DEFAULT_MAX_JOBS_ACTIVE = 32
    const val DEFAULT_LOCK_TIME_IN_SECONDS = 300L
    const val DEFAULT_RETRY_TIMEOUT_IN_SECONDS = 5L
  }

  private var listenerJobWorker: UserTaskListenerJobWorker? = null

  fun subscribe() {
    listenerJobWorker?.close()
    listenerJobWorker = UserTaskListenerJobWorker(
      camundaClient = camundaClient,
      topic = topic,
      workerId = workerId,
      maxJobsActive = maxJobsActive,
      streamEnabled = streamEnabled,
      lockTimeInSeconds = lockTimeInSeconds,
      fetchVariables = fetchVariablesForSubscriptions(),
      handler = this::consumeActivatedJob
    ).also {
      logger.trace { "PROCESS-ENGINE-C8-060: subscribing user task listener jobs for topic $topic." }
      it.open()
      logger.trace { "PROCESS-ENGINE-C8-061: subscribed user task listener jobs for topic $topic." }
    }
  }

  override fun unsubscribe(taskSubscription: TaskSubscription) {
    subscriptionRepository
      .getDeliveredTaskIds(TaskType.USER)
      .filter { taskId -> subscriptionRepository.getActiveSubscriptionForTask(taskId) == taskSubscription }
      .forEach { taskId -> subscriptionRepository.deactivateSubscriptionForTask(taskId) }
  }

  override fun close() {
    listenerJobWorker?.close()
    listenerJobWorker = null
  }

  internal fun consumeActivatedJob(job: ActivatedJob) {
    if (job.kind != JobKind.TASK_LISTENER || job.userTask == null) {
      logger.trace { "PROCESS-ENGINE-C8-062: ignoring non-user-task-listener job ${job.key} of type ${job.type}." }
      completeListenerJob(job)
      return
    }

    logger.warn { "PROCESS-ENGINE-C8-062: consuming user task listener job ${job.key} of type ${job.listenerEventType}." }
    when (job.listenerEventType) {
      ListenerEventType.CREATING -> createOrUpdate(job, TaskInformation.CREATE)
      ListenerEventType.ASSIGNING -> createOrUpdate(job, reasonForKnownTask(job, TaskInformation.ASSIGN))
      ListenerEventType.UPDATING -> createOrUpdate(job, reasonForKnownTask(job, TaskInformation.UPDATE))
      ListenerEventType.COMPLETING -> completeAndTerminate(job, TaskInformation.COMPLETE)
      ListenerEventType.CANCELING -> completeAndTerminate(job, TaskInformation.DELETE)
      else -> {
        logger.trace { "PROCESS-ENGINE-C8-063: completing unsupported user task listener event ${job.listenerEventType} for job ${job.key}." }
        completeListenerJob(job)
      }
    }
  }

  private fun createOrUpdate(job: ActivatedJob, reason: String) {
    val activeSubscription = subscriptionRepository
      .getTaskSubscriptions()
      .firstOrNull { subscription -> subscription.matches(job) }

    if (activeSubscription == null) {
      logger.trace { "PROCESS-ENGINE-C8-064: no user task subscription matched listener job ${job.key}." }
      completeListenerJob(job)
      return
    }

    val taskId = job.toUserTaskListenerEvent().first
    val variables = job.variablesAsMap.filterBySubscription(activeSubscription)
    val taskInformation = job.toUserTaskListenerTaskInformation().withReason(reason)

    try {
      logger.debug { "PROCESS-ENGINE-C8-065: delivering user task listener event ${job.listenerEventType} for task $taskId." }
      activeSubscription.action.accept(taskInformation, variables)
      subscriptionRepository.activateSubscriptionForTask(taskId, activeSubscription)
      completeListenerJob(job)
      logger.debug { "PROCESS-ENGINE-C8-066: delivered user task listener event ${job.listenerEventType} for task $taskId." }
    } catch (e: Exception) {
      logger.error(e) { "PROCESS-ENGINE-C8-067: failed to deliver user task listener event ${job.listenerEventType} for task $taskId." }
      failListenerJob(job, e.message ?: "Failed to deliver user task listener event")
    }
  }

  private fun completeAndTerminate(job: ActivatedJob, reason: String) {
    val taskId = job.toUserTaskListenerEvent().first
    completeListenerJob(job)

    subscriptionRepository.deactivateSubscriptionForTask(taskId)?.apply {
      try {
        logger.debug { "PROCESS-ENGINE-C8-068: terminating user task listener delivery for task $taskId with reason $reason." }
        termination.accept(job.toUserTaskListenerTaskInformation().withReason(reason))
      } catch (e: Exception) {
        logger.error(e) { "PROCESS-ENGINE-C8-069: failed to terminate user task listener delivery for task $taskId." }
      }
    }
  }

  private fun reasonForKnownTask(job: ActivatedJob, reason: String): String {
    val taskId = job.toUserTaskListenerEvent().first
    return if (subscriptionRepository.getActiveSubscriptionForTask(taskId) == null) {
      TaskInformation.CREATE
    } else {
      reason
    }
  }

  private fun completeListenerJob(job: ActivatedJob) {
    camundaClient
      .newCompleteCommand(job.key)
      .send()
      .join()
  }

  private fun failListenerJob(job: ActivatedJob, reason: String) {
    camundaClient
      .newFailCommand(job.key)
      .retries((job.retries - 1).coerceAtLeast(0))
      .retryBackoff(Duration.ofSeconds(retryTimeoutInSeconds))
      .errorMessage(reason)
      .send()
      .join()
  }

  private fun fetchVariablesForSubscriptions(): List<String>? {
    val payloadDescriptions = subscriptionRepository
      .getTaskSubscriptions()
      .filter { it.taskType == TaskType.USER }
      .map { it.payloadDescription }

    return if (payloadDescriptions.any { it == null }) {
      null // fetch all variables
    } else {
      payloadDescriptions
        .flatMap { it.orEmpty() }
        .distinct()
        .sorted()
    }
  }


  fun TaskSubscriptionHandle.matches(job: ActivatedJob): Boolean {
    return job.kind == JobKind.TASK_LISTENER
      && job.userTask != null
      && this.taskType == TaskType.USER
      && (this.taskDescriptionKey == null || this.taskDescriptionKey == job.elementId)
      && this.restrictions
      .minus(
        CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS
      )
      .all {
        when (it.key) {
          CommonRestrictions.EXECUTION_ID -> it.value == "${job.elementInstanceKey}"
          CommonRestrictions.ACTIVITY_ID -> it.value == job.elementId
          CommonRestrictions.TENANT_ID -> it.value == job.tenantId
          CommonRestrictions.PROCESS_INSTANCE_ID -> it.value == "${job.processInstanceKey}"
          CommonRestrictions.PROCESS_DEFINITION_ID -> it.value == "${job.processDefinitionKey}"
          CommonRestrictions.PROCESS_DEFINITION_KEY -> it.value == job.bpmnProcessId
          else -> false
        }
      }
  }

}
