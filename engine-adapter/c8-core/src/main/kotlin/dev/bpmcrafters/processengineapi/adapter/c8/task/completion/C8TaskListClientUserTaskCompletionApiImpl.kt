package dev.bpmcrafters.processengineapi.adapter.c8.task.completion

import dev.bpmcrafters.processengineapi.Empty
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import io.camunda.tasklist.CamundaTaskListClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger {}

class C8TaskListClientUserTaskCompletionApiImpl(
  private val taskListClient: CamundaTaskListClient,
  private val subscriptionRepository: SubscriptionRepository
) : UserTaskCompletionApi {

  override fun completeTask(cmd: CompleteTaskCmd): CompletableFuture<Empty> {
    logger.debug { "PROCESS-ENGINE-C8-006: completing service task ${cmd.taskId}." }
    taskListClient.completeTask(cmd.taskId, cmd.get())
    subscriptionRepository.deactivateSubscriptionForTask(cmd.taskId)?.apply {
      termination.accept(TaskInformation(cmd.taskId, emptyMap()).withReason(TaskInformation.COMPLETE))
      logger.debug { "PROCESS-ENGINE-C8-007: successfully completed service task ${cmd.taskId}." }
    }
    return CompletableFuture.completedFuture(Empty)
  }

  override fun completeTaskByError(cmd: CompleteTaskByErrorCmd): CompletableFuture<Empty> {
    TODO("Not supported by task list client")
  }
}
