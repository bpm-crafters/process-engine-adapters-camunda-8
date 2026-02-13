package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
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

  override fun refresh() {
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
              subscriptionRepository.activateSubscriptionForTask(taskId, activeSubscription)

              val form = task.formKey?.let { camundaClient.newUserTaskGetFormRequest(task.userTaskKey).send().join() }
              val taskInformation = task.toTaskInformation(form).withReason(
                if (deliveredTaskIds.contains(taskId) && subscriptionRepository.getActiveSubscriptionForTask(taskId) == activeSubscription) {
                  // remove from already delivered
                  deliveredTaskIds.remove(taskId)
                  // task was already delivered to this subscription
                  TaskInformation.UPDATE
                } else {
                  // task is new for this subscription
                  TaskInformation.CREATE
                }
              )

              deliveredTaskIds.remove(taskId)

              val loadedVariables = camundaClient.newUserTaskVariableSearchRequest(task.userTaskKey).send().join().items()
              val variablesFromTask: Map<String, Any?> = loadedVariables.associate { variable ->
                variable.name to variable.value
              }

              val variables = variablesFromTask.filterBySubscription(activeSubscription)

              try {
                logger.debug { "PROCESS-ENGINE-C8-031: delivering user task ${taskId}." }
                activeSubscription.action.accept(taskInformation, variables)
                logger.debug { "PROCESS-ENGINE-C8-032: successfully delivered user task ${taskId}." }
              } catch (e: Exception) {
                logger.error { "PROCESS-ENGINE-C8-031: error delivering user task ${taskId}: ${e.message}" }
                subscriptionRepository.deactivateSubscriptionForTask(taskId = taskId)
              }
            }
        }
    } else {
      logger.trace { "PROCESS-ENGINE-C8-035: pulling user tasks disabled, no subscriptions." }
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
      && this.restrictions.all {
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
