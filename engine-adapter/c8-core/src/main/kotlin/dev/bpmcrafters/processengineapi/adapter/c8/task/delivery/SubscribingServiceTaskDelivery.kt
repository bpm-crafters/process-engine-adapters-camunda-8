package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.impl.task.filterBySubscription
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Uses task subscription available in the repository to subscribe to zeebe.
 */
class SubscribingServiceTaskDelivery(
  private val camundaClient: CamundaClient,
  private val subscriptionRepository: SubscriptionRepository,
  private val workerId: String,
    private val retryTimeoutInSeconds: Long
) {

  fun subscribe() {
    val subscriptions = subscriptionRepository.getTaskSubscriptions().filter { s -> s.taskType == TaskType.EXTERNAL }
    if (subscriptions.isNotEmpty()) {
      logger.trace { "PROCESS-ENGINE-C8-050: subscribing service tasks for subscriptions: $subscriptions" }
      subscriptions
        .filter { it.taskType == TaskType.EXTERNAL }
        .forEach { activeSubscription ->
          // this is a job to subscribe to.
          camundaClient
            .newWorker()
            .jobType(activeSubscription.taskDescriptionKey)
            .handler { _, job -> consumeActivatedJob(activeSubscription, job, camundaClient) }
            .name(workerId)
            .forSubscription(activeSubscription)
            // FIXME -> metrics to setup
            .open()
        }
    } else {
      logger.trace { "PROCESS-ENGINE-C8-050: Not subscribing service tasks for subscriptions. No subscriptions found." }
    }
  }

  private fun consumeActivatedJob(activeSubscription: TaskSubscriptionHandle, job: ActivatedJob, camundaClient: CamundaClient) {
    if (activeSubscription.matches(job)) {
      subscriptionRepository.activateSubscriptionForTask("${job.key}", activeSubscription)
      val variables = job.variablesAsMap.filterBySubscription(activeSubscription)
      try {
        logger.debug { "PROCESS-ENGINE-C8-051: Delivering service task ${job.key}." }
        activeSubscription.action.accept(job.toTaskInformation().withReason(TaskInformation.CREATE), variables)
        logger.debug { "PROCESS-ENGINE-C8-052: Successfully delivered service task ${job.key}." }
      } catch (e: Exception) {
        logger.error { "PROCESS-ENGINE-C8-051: Failing to deliver service task ${job.key}: ${e.message}." }
        camundaClient.newFailCommand(job.key).retries(job.retries)
          .retryBackoff(Duration.ofSeconds(retryTimeoutInSeconds)).send().join() // could not deliver
        subscriptionRepository.deactivateSubscriptionForTask(taskId = "${job.key}")
        logger.error { "PROCESS-ENGINE-C8-052: Successfully failed to deliver service task ${job.key}: ${e.message}." }
      }
    } else {
      // put it back
      // TODO: check this, is it ok to put the job this way back?
      logger.trace { "PROCESS-ENGINE-C8-053: Received service task ${job.key} not matching subscriptions, returning it." }
      camundaClient.newFailCommand(job.key).retries(job.retries + 1)
        .retryBackoff(Duration.ofSeconds(retryTimeoutInSeconds)).send().join()
      logger.trace { "PROCESS-ENGINE-C8-045: Successfully returned service task ${job.key} not matching subscriptions." }
    }

  }

  /*
   * Additional restrictions to check.
   * The activated job can be completed by the Subscription strategy and is correct type (topic).
   */
  private fun TaskSubscriptionHandle.matches(job: ActivatedJob): Boolean {
    return this.taskType == TaskType.EXTERNAL
      && (this.taskDescriptionKey == null || this.taskDescriptionKey == job.type)
      && this.restrictions.all {
      when (it.key) {
        CommonRestrictions.EXECUTION_ID -> it.value == "${job.elementInstanceKey}"
        CommonRestrictions.ACTIVITY_ID -> it.value == job.elementId
        CommonRestrictions.TENANT_ID -> it.value == job.tenantId
        CommonRestrictions.PROCESS_INSTANCE_ID -> it.value == "${job.processInstanceKey}"
        CommonRestrictions.PROCESS_DEFINITION_ID -> it.value == "${job.processDefinitionKey}"
        else -> false
      }
    }
    // job.customHeaders // FIXME: analyze this! user/service task, etc..
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
}
