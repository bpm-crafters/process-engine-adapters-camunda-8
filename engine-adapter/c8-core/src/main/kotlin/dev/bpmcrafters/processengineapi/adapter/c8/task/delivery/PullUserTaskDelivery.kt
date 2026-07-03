package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.adapter.c8.task.asJson
import dev.bpmcrafters.processengineapi.adapter.c8.task.parseJson
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.impl.task.filterBySubscription
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.search.enums.UserTaskState
import io.camunda.client.api.search.filter.UserTaskFilter
import io.camunda.client.api.search.response.UserTask
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class PullUserTaskDelivery(
  private val camundaClient: CamundaClient,
  private val subscriptionRepository: SubscriptionRepository
) : RefreshableDelivery {

  private val refreshMonitor = Any()

  override fun refresh() = synchronized(refreshMonitor) {
    val subscriptions = subscriptionRepository.getTaskSubscriptions().filter { s -> s.taskType == TaskType.USER }
    // FIXME -> reverse lookup for all active subscriptions
    // if the task is not retrieved but active subscription has a task, call modification#terminated hook
    if (subscriptions.isNotEmpty()) {
      val deliveredTaskIds = subscriptionRepository.getDeliveredTaskIds(TaskType.USER).toMutableList()
      logger.trace { "PROCESS-ENGINE-C8-030: pulling user tasks for subscriptions: $subscriptions" }
      camundaClient.newUserTaskSearchRequest()
        .filter {
          it.forSubscriptions(subscriptions)
            .state(UserTaskState.CREATED)
        }
        .send().join().items()
        .forEach { task ->
          subscriptions
            .firstOrNull { subscription -> subscription.matches(task) }
            ?.let { activeSubscription ->

              val taskId = task.userTaskKey.toString()
              val wasDeliveredToSubscription =
                deliveredTaskIds.contains(taskId) &&
                  subscriptionRepository.getActiveSubscriptionForTask(taskId) == activeSubscription
              try {
                val form = task.formKey?.let { camundaClient.newUserTaskGetFormRequest(task.userTaskKey).send().join() }
                val taskInformation = task.toTaskInformation(form).withReason(
                  if (wasDeliveredToSubscription) {
                    TaskInformation.UPDATE
                  } else {
                    TaskInformation.CREATE
                  }
                )

                val variableRequest = camundaClient.newUserTaskVariableSearchRequest(task.userTaskKey).apply {
                  if (!activeSubscription.payloadDescription.isNullOrEmpty()) {
                    this.filter { it.name { variable -> variable.`in`(activeSubscription.payloadDescription!!.toList()) } }
                  }
                }

                val result = variableRequest.withFullValues().send().join().items()

                val variablesFromTask = result.associate { variable ->
                  variable.name to variable.value
                }.asJson().parseJson(jacksonObjectMapper())

                val variables = variablesFromTask.filterBySubscription(activeSubscription)

                subscriptionRepository.activateSubscriptionForTask(taskId, activeSubscription)
                logger.debug { "PROCESS-ENGINE-C8-031: delivering user task ${taskId}." }
                activeSubscription.action.accept(taskInformation, variables)
                logger.debug { "PROCESS-ENGINE-C8-032: successfully delivered user task ${taskId}." }
              } catch (e: Exception) {
                logger.error(e) { "PROCESS-ENGINE-C8-031: error delivering user task ${taskId}: ${e.message}" }
                deactivateAndTerminate(taskId)
              } finally {
                deliveredTaskIds.remove(taskId)
              }
            }
        }
      deliveredTaskIds.forEach { taskId ->
        if (subscriptionRepository.getActiveSubscriptionForTask(taskId) != null) {
          logger.trace { "PROCESS-ENGINE-C8-033: User task is gone, sending termination to the handler." }
          deactivateAndTerminate(taskId)
          logger.trace { "PROCESS-ENGINE-C8-034: Termination sent to handler and user task is removed." }
        }
      }
    } else {
      logger.trace { "PROCESS-ENGINE-C8-035: pulling user tasks disabled, no subscriptions." }
    }
  }

  private fun deactivateAndTerminate(taskId: String) {
    subscriptionRepository.deactivateSubscriptionForTask(taskId)?.let {
      try {
        it.termination.accept(TaskInformation(taskId, mapOf()).withReason(TaskInformation.DELETE))
      } catch (terminationError: Exception) {
        logger.error(terminationError) { "PROCESS-ENGINE-C8-036: failed to terminate user task ${taskId} during cleanup." }
      }
    }
  }

  private fun UserTaskFilter.forSubscriptions(subscriptions: List<TaskSubscriptionHandle>): UserTaskFilter {
    // FIXME -> support tenant on subscription
    // FIXME -> consider complex tent filtering
    return this
  }

  private fun TaskSubscriptionHandle.matches(task: UserTask): Boolean =
    this.taskType == TaskType.USER
      && (this.taskDescriptionKey == null || this.taskDescriptionKey == task.elementId)
      && this.restrictions
      .minus( // ignore some restrictions that are not relevant for external tasks or handled differntly
        CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS
      )
      .all {
        when (it.key) {
          CommonRestrictions.TENANT_ID -> it.value == task.tenantId
          CommonRestrictions.PROCESS_INSTANCE_ID -> it.value == task.processInstanceKey.toString()
          CommonRestrictions.PROCESS_DEFINITION_ID -> it.value == task.processDefinitionKey.toString()
          CommonRestrictions.PROCESS_DEFINITION_KEY -> it.value == task.bpmnProcessId
          // FIXME -> more restrictions
          else -> false
        }
      }
}
