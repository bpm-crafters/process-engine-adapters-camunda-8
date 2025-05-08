package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.impl.task.filterBySubscription
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.tasklist.CamundaTaskListClient
import io.camunda.tasklist.dto.Task
import io.camunda.tasklist.dto.TaskSearch
import io.camunda.tasklist.dto.TaskState
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class PullUserTaskDelivery(
  private val taskListClient: CamundaTaskListClient,
  private val subscriptionRepository: SubscriptionRepository
) : RefreshableDelivery {

  override fun refresh() {
    val subscriptions = subscriptionRepository.getTaskSubscriptions().filter { s -> s.taskType == TaskType.USER }
    // FIXME -> reverse lookup for all active subscriptions
    // if the task is not retrieved but active subscription has a task, call modification#terminated hook
    if (subscriptions.isNotEmpty()) {
      val deliveredTaskIds = subscriptionRepository.getDeliveredTaskIds(TaskType.USER).toMutableList()
      logger.trace { "PROCESS-ENGINE-C8-030: pulling user tasks for subscriptions: $subscriptions" }
      taskListClient.getTasks(
        TaskSearch()
          .forSubscriptions(subscriptions)
          .setWithVariables(true)
          .setState(TaskState.CREATED) // deliver only open tasks
      ).forEach { task ->
        subscriptions
          .firstOrNull { subscription -> subscription.matches(task) }
          ?.let { activeSubscription ->

            subscriptionRepository.activateSubscriptionForTask(task.id, activeSubscription)

            val taskInformation = task.toTaskInformation().withReason(
              if (deliveredTaskIds.contains(task.id) && subscriptionRepository.getActiveSubscriptionForTask(task.id) == activeSubscription) {
                // remove from already delivered
                deliveredTaskIds.remove(task.id)
                // task was already delivered to this subscription
                TaskInformation.UPDATE
              } else {
                // task is new for this subscription
                TaskInformation.CREATE
              }
            )

            deliveredTaskIds.remove(task.id)
            val variablesFromTask: Map<String, Any> = task.variables?.associate { variable ->
              variable.name to variable.value
            } ?: mapOf()

            val variables = variablesFromTask.filterBySubscription(activeSubscription)

            try {
              logger.debug { "PROCESS-ENGINE-C8-031: delivering user task ${task.id}." }
              activeSubscription.action.accept(taskInformation, variables)
              logger.debug { "PROCESS-ENGINE-C8-032: successfully delivered user task ${task.id}." }
            } catch (e: Exception) {
              logger.error { "PROCESS-ENGINE-C8-031: error delivering user task ${task.id}: ${e.message}" }
              subscriptionRepository.deactivateSubscriptionForTask(taskId = task.id)
            }
          }
      }
    } else {
      logger.trace { "PROCESS-ENGINE-C8-035: pulling user tasks disabled, no subscriptions." }
    }
  }

  private fun TaskSearch.forSubscriptions(subscriptions: List<TaskSubscriptionHandle>): TaskSearch {
    // FIXME -> support tenant on subscription
    // FIXME -> consider complex tent filtering
    return this
  }

  private fun TaskSubscriptionHandle.matches(task: Task): Boolean =
    this.taskType == TaskType.USER
      && (this.taskDescriptionKey == null || this.taskDescriptionKey == task.taskDefinitionId)
      && this.restrictions.all {
      when (it.key) {
        CommonRestrictions.TENANT_ID -> it.value == task.tenantId
        CommonRestrictions.PROCESS_INSTANCE_ID -> it.value == task.processInstanceKey
        CommonRestrictions.PROCESS_DEFINITION_ID -> it.value == task.processDefinitionKey
        // FIXME -> more restrictions
        else -> false
      }
    }
}
