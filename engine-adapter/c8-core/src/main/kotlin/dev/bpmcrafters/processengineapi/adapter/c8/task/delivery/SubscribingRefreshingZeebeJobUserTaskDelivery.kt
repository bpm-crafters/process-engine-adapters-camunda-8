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
import io.camunda.client.api.command.ActivateJobsCommandStep1.ActivateJobsCommandStep3
import io.camunda.client.api.command.ClientHttpException
import io.camunda.client.api.command.ClientStatusException
import io.camunda.client.api.command.StreamJobsCommandStep1.StreamJobsCommandStep3
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3
import io.camunda.zeebe.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Status
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

class SubscribingRefreshingZeebeJobUserTaskDelivery(
  private val camundaClient: CamundaClient,
  private val subscriptionRepository: SubscriptionRepository,
  private val workerId: String,
  private val userTaskLockTimeoutMs: Long,
) : SubscribingUserTaskDelivery, RefreshableDelivery {

  private class RegisteredWorker {
    val acceptingJobs = AtomicBoolean(true)
    // Cancellation may precede open(); only one side may claim the attached worker for closing.
    private val worker = AtomicReference<JobWorker?>()

    fun attach(openedWorker: JobWorker) {
      worker.set(openedWorker)
      if (!acceptingJobs.get()) worker.getAndSet(null)?.close()
    }

    fun cancel(): JobWorker? {
      acceptingJobs.set(false)
      return worker.getAndSet(null)
    }
  }

  private val jobWorkerRegistry = mutableMapOf<TaskSubscription, RegisteredWorker>()
  private val lifecycleMonitor = Any()
  private val refreshMonitor = Any()

  fun subscribe() {
    // Publish the whole batch before opening so callbacks can cancel pending workers too.
    val registrations = synchronized(lifecycleMonitor) {
      subscriptionRepository.getTaskSubscriptions()
        .filter { it.taskType == TaskType.USER }
        .map { subscription ->
          val registration = RegisteredWorker()
          jobWorkerRegistry[subscription] = registration
          subscription to registration
        }
    }
    if (registrations.isEmpty()) {
      logger.trace { "PROCESS-ENGINE-C8-046: not subscribing for user tasks, no active subscription found." }
      return
    }
    logger.trace { "PROCESS-ENGINE-C8-040: subscribing user tasks for subscriptions: ${registrations.map { it.first }}" }
    try {
      registrations.forEach { (activeSubscription, registration) ->
        if (registration.acceptingJobs.get()) {
          val subscribedJobWorker = camundaClient
            .newWorker()
            .jobType(Protocol.USER_TASK_JOB_TYPE)
            .handler { _, job -> consumeActivatedJob(activeSubscription, job, camundaClient, registration.acceptingJobs) }
            .maxJobsActive(Integer.MAX_VALUE)
            .name(workerId)
            .timeout(userTaskLockTimeoutMs)
            .streamEnabled(false)
            .forSubscription(activeSubscription)
            // FIXME -> metrics to setup
            .open()
          registration.attach(subscribedJobWorker)
        }
      }
    } catch (error: Throwable) {
      val workers = synchronized(lifecycleMonitor) {
        registrations.mapNotNull { (subscription, registration) ->
          val worker = registration.cancel()
          jobWorkerRegistry.remove(subscription, registration)
          worker
        }
      }
      closeWorkers(workers, error)
    }
  }

  private fun consumeActivatedJob(
    activeSubscription: TaskSubscriptionHandle,
    job: ActivatedJob,
    zeebeClient: CamundaClient,
    acceptingJobs: AtomicBoolean
  ) {
    // Closing a worker does not prevent outstanding poll responses from invoking its handler.
    if (!acceptingJobs.get()) {
      logger.trace { "PROCESS-ENGINE-C8-057: Returning user task ${job.key} received after unsubscribe." }
      zeebeClient.newFailCommand(job.key).retries(job.retries).send().join()
      return
    }
    if (activeSubscription.matches(job)) {
      val taskId = "${job.key}"
      subscriptionRepository.activateSubscriptionForTask(taskId, activeSubscription)
      val variables = job.variablesAsMap.filterBySubscription(activeSubscription)
      try {
        logger.debug { "PROCESS-ENGINE-C8-041: Delivering user task ${job.key}." }
        activeSubscription.action.accept(job.toTaskInformation().withReason(TaskInformation.CREATE), variables)
        logger.debug { "PROCESS-ENGINE-C8-042: Successfully delivered user task ${job.key}." }
      } catch (e: Exception) {
        logger.error(e) { "PROCESS-ENGINE-C8-043: Failed to deliver user task ${job.key}: ${e.message}" }
        try {
          zeebeClient
            .newFailCommand(job.key)
            .retries(job.retries)
            .send()
            .join() // could not deliver
        } finally {
          deactivateAndTerminate(taskId)
        }
      }
    } else {
      // put it back
      logger.trace { "PROCESS-ENGINE-C8-044: Received user task ${job.key} not matching subscriptions, returning it." }
      zeebeClient
        .newFailCommand(job.key)
        .retries(job.retries + 1)
        .send()
        .join()
      logger.trace { "PROCESS-ENGINE-C8-045: Successfully returned user task ${job.key} not matching subscriptions." }
    }
  }

  override fun refresh() = synchronized(refreshMonitor) {
    val subscriptions = subscriptionRepository.getDeliveredTaskIds(TaskType.USER)
    logger.trace { "PROCESS-ENGINE-C8-047: refreshing user tasks for subscriptions: $subscriptions" }
    if (subscriptions.isNotEmpty()) {
      subscriptions.forEach { taskId ->
        try {
          logger.trace { "PROCESS-ENGINE-C8-048: Extending job timeout for user task $taskId..." }
          camundaClient
            .newUpdateTimeoutCommand(taskId.toLong())
            .timeout(userTaskLockTimeoutMs)
            .send()
            .join()
          logger.trace { "PROCESS-ENGINE-C8-049: Extended job timeout for user task $taskId." }
        } catch (e: ClientStatusException) {
          when (e.statusCode) {
            Status.Code.NOT_FOUND -> {
              logger.trace { "PROCESS-ENGINE-C8-050: User task is gone, sending termination to the handler." }
              deactivateAndTerminate(taskId)
              logger.trace { "PROCESS-ENGINE-C8-051: Termination sent to handler and user task is removed." }
            }

            else -> logger.error(e) { "PROCESS-ENGINE-C8-052: Error extending job $taskId, ${e.message}." }
          }
        } catch (e: ClientHttpException) {
          if (e.code() == 404) {
            logger.trace { "PROCESS-ENGINE-C8-050: User task is gone, sending termination to the handler." }
            deactivateAndTerminate(taskId)
            logger.trace { "PROCESS-ENGINE-C8-051: Termination sent to handler and user task is removed." }
          } else {
            logger.error(e) { "PROCESS-ENGINE-C8-052: Error extending job $taskId, ${e.message}." }
          }
        }
      }
    } else {
      logger.trace { "PROCESS-ENGINE-C8-053: not subscribing for user tasks, no active subscription found." }
    }
  }

  private fun deactivateAndTerminate(taskId: String) {
    subscriptionRepository.deactivateSubscriptionForTask(taskId)?.let {
      try {
        it.termination.accept(TaskInformation(taskId, mapOf()).withReason(TaskInformation.DELETE))
      } catch (terminationError: Exception) {
        logger.error(terminationError) { "PROCESS-ENGINE-C8-056: Failed to terminate user task $taskId during cleanup." }
      }
    }
  }

  override fun unsubscribe(taskSubscription: TaskSubscription) {
    logger.debug { "PROCESS-ENGINE-C8-054: Unsubscribe from user task: $taskSubscription" }
    val worker = synchronized(lifecycleMonitor) {
      jobWorkerRegistry.remove(taskSubscription)?.cancel()
    }
    worker?.close()
  }

  fun unsubscribeAll() {
    logger.debug { "PROCESS-ENGINE-C8-055: Unsubscribe all user tasks." }
    val workers = synchronized(lifecycleMonitor) {
      jobWorkerRegistry.values.mapNotNull { it.cancel() }.also { jobWorkerRegistry.clear() }
    }
    closeWorkers(workers)
  }

  private fun closeWorkers(workers: List<JobWorker>, initialFailure: Throwable? = null) {
    var failure = initialFailure
    workers.forEach { worker ->
      try {
        worker.close()
      } catch (error: Throwable) {
        val previousFailure = failure
        if (previousFailure == null) {
          failure = error
        } else if (previousFailure !== error) {
          previousFailure.addSuppressed(error)
        }
      }
    }
    failure?.let { throw it }
  }

  /*
   * Additional restrictions to check.
   */
  internal fun TaskSubscriptionHandle.matches(job: ActivatedJob): Boolean {
    return this.taskType == TaskType.USER
      && (this.taskDescriptionKey == null || this.taskDescriptionKey == job.elementId)
      && this.restrictions
      .minus( // ignore some restrictions that are not relevant for external tasks or handled differntly
        CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS
      )
      .all {
      when (it.key) {
        CommonRestrictions.EXECUTION_ID -> it.value == "${job.elementInstanceKey}"
        CommonRestrictions.ACTIVITY_ID -> it.value == job.elementId
        CommonRestrictions.TENANT_ID -> it.value == job.tenantId
        CommonRestrictions.PROCESS_DEFINITION_KEY -> it.value == job.bpmnProcessId
        CommonRestrictions.PROCESS_DEFINITION_ID -> it.value == "${job.processDefinitionKey}"
        CommonRestrictions.PROCESS_INSTANCE_ID -> it.value == "${job.processInstanceKey}"
        else -> false
      }
    }
    // job.customHeaders // FIXME: analyze this! make sure we reflect the subscription restrictions
  }

  private fun ActivateJobsCommandStep3.forSubscription(subscription: TaskSubscriptionHandle): ActivateJobsCommandStep3 {
    // FIXME -> more to setup from props
    return this.apply {
      val payloadDescription = subscription.payloadDescription
      if (!payloadDescription.isNullOrEmpty()) {
        this.fetchVariables(payloadDescription.toList())
      }
      if (subscription.restrictions.containsKey(CommonRestrictions.TENANT_ID)) {
        this.tenantId(subscription.restrictions[CommonRestrictions.TENANT_ID])
      }
    }
  }

  private fun JobWorkerBuilderStep3.forSubscription(subscription: TaskSubscriptionHandle): JobWorkerBuilderStep3 {
    // FIXME -> more to setup from props
    return this.apply {
      val payloadDescription = subscription.payloadDescription
      if (!payloadDescription.isNullOrEmpty()) {
        this.fetchVariables(payloadDescription.toList())
      }
      if (subscription.restrictions.containsKey(CommonRestrictions.TENANT_ID)) {
        this.tenantId(subscription.restrictions[CommonRestrictions.TENANT_ID])
      }
    }
  }

  private fun StreamJobsCommandStep3.forSubscription(subscription: TaskSubscriptionHandle): StreamJobsCommandStep3 {
    // FIXME -> more to setup from props
    return this.apply {
      val payloadDescription = subscription.payloadDescription
      if (!payloadDescription.isNullOrEmpty()) {
        this.fetchVariables(payloadDescription.toList())
      }
      if (subscription.restrictions.containsKey(CommonRestrictions.TENANT_ID)) {
        this.tenantId(subscription.restrictions[CommonRestrictions.TENANT_ID])
      }
    }
  }

}
