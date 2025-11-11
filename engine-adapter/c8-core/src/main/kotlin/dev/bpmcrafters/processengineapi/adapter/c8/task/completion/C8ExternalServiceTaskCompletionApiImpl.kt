package dev.bpmcrafters.processengineapi.adapter.c8.task.completion

import dev.bpmcrafters.processengineapi.Empty
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.task.*
import io.camunda.client.CamundaClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger {}

class C8ExternalServiceTaskCompletionApiImpl(
  private val camundaClient: CamundaClient,
  private val subscriptionRepository: SubscriptionRepository,
  private val failureRetrySupplier: FailureRetrySupplier
) : ServiceTaskCompletionApi {

  override fun completeTask(cmd: CompleteTaskCmd): CompletableFuture<Empty> {
    logger.debug { "PROCESS-ENGINE-C8-008: completing service task ${cmd.taskId}." }
    camundaClient
      .newCompleteCommand(cmd.taskId.toLong())
      .variables(cmd.get())
      .send()
      .join()
    subscriptionRepository.deactivateSubscriptionForTask(cmd.taskId)?.apply {
      termination.accept(TaskInformation(cmd.taskId, emptyMap()).withReason(TaskInformation.COMPLETE))
      logger.debug { "PROCESS-ENGINE-C8-009: successfully completed service task ${cmd.taskId}." }
    }
    return CompletableFuture.completedFuture(Empty)
  }

  override fun completeTaskByError(cmd: CompleteTaskByErrorCmd): CompletableFuture<Empty> {
    logger.debug { "PROCESS-ENGINE-C8-008: throwing error ${cmd.errorCode} in service task ${cmd.taskId}." }
    camundaClient
      .newThrowErrorCommand(cmd.taskId.toLong())
      .errorCode(cmd.errorCode)
      .errorMessage(cmd.errorMessage ?: "Unknown error")
      .variables(cmd.get())
      .send()
      .join()
    subscriptionRepository.deactivateSubscriptionForTask(cmd.taskId)?.apply {
      logger.debug { "PROCESS-ENGINE-C8-009: successfully thrown error ${cmd.errorCode} in service task ${cmd.taskId}." }
      termination.accept(TaskInformation(cmd.taskId, emptyMap()).withReason(TaskInformation.COMPLETE))
    }
    return CompletableFuture.completedFuture(Empty)
  }

  override fun failTask(cmd: FailTaskCmd): CompletableFuture<Empty> {
    val (retries, retriesTimeout) = failureRetrySupplier.apply(cmd.taskId)
    camundaClient
      .newFailCommand(cmd.taskId.toLong())
      .retries(cmd.retryCount ?: retries)
      .retryBackoff(cmd.retryBackoff ?: Duration.ofSeconds(retriesTimeout))
      .errorMessage(cmd.reason)
      .send()
      .join()
    logger.debug { "PROCESS-ENGINE-C8-010: failing service task ${cmd.taskId}." }
    subscriptionRepository.deactivateSubscriptionForTask(cmd.taskId)?.apply {
      logger.debug { "PROCESS-ENGINE-C8-011: successfully failed service task ${cmd.taskId}." }
      termination.accept(TaskInformation(cmd.taskId, emptyMap()).withReason(TaskInformation.COMPLETE))
    }
    return CompletableFuture.completedFuture(Empty)
  }
}
