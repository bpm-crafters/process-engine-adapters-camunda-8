package dev.bpmcrafters.processengineapi.adapter.c8.task.completion

import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd
import dev.bpmcrafters.processengineapi.task.FailTaskCmd
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.CompleteJobCommandStep1
import io.camunda.client.api.command.FailJobCommandStep1
import io.camunda.client.api.command.FailJobCommandStep1.FailJobCommandStep2
import io.camunda.client.api.command.ThrowErrorCommandStep1
import io.camunda.client.api.command.ThrowErrorCommandStep1.ThrowErrorCommandStep2
import io.camunda.client.api.response.CompleteJobResponse
import io.camunda.client.api.response.FailJobResponse
import io.camunda.client.api.response.ThrowErrorResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration


class C8ExternalServiceTaskCompletionApiImplTest {

  companion object {
    const val TASK_ID = "1234"
    const val REASON = "because"
    const val RETRIES = 1
    const val BACKOFF = 2L
    const val ERROR_CODE = "0815"
    val PAYLOAD = mapOf("foo" to "bar")
  }

  private val camundaClient: CamundaClient = mock()
  private val subscriptionRepository: SubscriptionRepository = mock()
  private val failureRetrySupplier: FailureRetrySupplier = mock()

  private val taskCompletionApi = C8ExternalServiceTaskCompletionApiImpl(
    camundaClient,
    subscriptionRepository,
    failureRetrySupplier
  )

  @Test
  fun `complete task in Camunda`() {
    // GIVEN
    val completeJobCommandStep1: CompleteJobCommandStep1 = mock()
    val zeebeFuture: CamundaFuture<CompleteJobResponse> = mock()

    whenever(failureRetrySupplier.apply(any())).thenReturn(FailureRetrySupplier.FailureRetry(RETRIES, BACKOFF))
    whenever(camundaClient.newCompleteCommand(TASK_ID.toLong())).thenReturn(completeJobCommandStep1)
    whenever(completeJobCommandStep1.variables(PAYLOAD)).thenReturn(completeJobCommandStep1)
    whenever(completeJobCommandStep1.send()).thenReturn(zeebeFuture)

    // WHEN
    taskCompletionApi.completeTask(
      CompleteTaskCmd(
        taskId = TASK_ID,
         payloadSupplier = { PAYLOAD}
      )
    )

    verify(completeJobCommandStep1).variables(PAYLOAD)
    verify(completeJobCommandStep1).send()
  }

  @Test
  fun `fail task in Camunda`() {
    // GIVEN
    val failJobCommandStep1: FailJobCommandStep1 = mock()
    val failJobCommandStep2: FailJobCommandStep2 = mock()
    val zeebeFuture: CamundaFuture<FailJobResponse> = mock()

    whenever(failureRetrySupplier.apply(any())).thenReturn(FailureRetrySupplier.FailureRetry(RETRIES, BACKOFF))
    whenever(camundaClient.newFailCommand(TASK_ID.toLong())).thenReturn(failJobCommandStep1)
    whenever(failJobCommandStep1.retries(any())).thenReturn(failJobCommandStep2)
    whenever(failJobCommandStep2.retryBackoff(any())).thenReturn(failJobCommandStep2)
    whenever(failJobCommandStep2.errorMessage(any())).thenReturn(failJobCommandStep2)
    whenever(failJobCommandStep2.send()).thenReturn(zeebeFuture)

    // WHEN
    taskCompletionApi.failTask(
      FailTaskCmd(
        taskId = TASK_ID,
        reason = REASON,
        errorDetails = null
      )
    )

    // THEN
    verify(failJobCommandStep1).retries(RETRIES)
    verify(failJobCommandStep2).retryBackoff(Duration.ofSeconds(BACKOFF))
    verify(failJobCommandStep2).errorMessage(REASON)
    verify(failJobCommandStep2).send()
  }

  @Test
  fun `fail task takes retries from fail task command in Zeebe`() {
    // GIVEN
    val failJobCommandStep1: FailJobCommandStep1 = mock()
    val failJobCommandStep2: FailJobCommandStep2 = mock()
    val zeebeFuture: CamundaFuture<FailJobResponse> = mock()

    whenever(failureRetrySupplier.apply(any())).thenReturn(FailureRetrySupplier.FailureRetry(RETRIES, BACKOFF))
    whenever(camundaClient.newFailCommand(TASK_ID.toLong())).thenReturn(failJobCommandStep1)
    whenever(failJobCommandStep1.retries(any())).thenReturn(failJobCommandStep2)
    whenever(failJobCommandStep2.retryBackoff(any())).thenReturn(failJobCommandStep2)
    whenever(failJobCommandStep2.errorMessage(any())).thenReturn(failJobCommandStep2)
    whenever(failJobCommandStep2.send()).thenReturn(zeebeFuture)

    val retryCount = 3
    val retryBackoff = Duration.ofSeconds(10)

    // WHEN
    taskCompletionApi.failTask(
      FailTaskCmd(
        taskId = TASK_ID,
        reason = REASON,
        errorDetails = null,
        retryCount = retryCount,
        retryBackoff = retryBackoff
      )
    )

    // THEN
    verify(failJobCommandStep1).retries(retryCount)
    verify(failJobCommandStep2).retryBackoff(retryBackoff)
    verify(failJobCommandStep2).errorMessage(REASON)
    verify(failJobCommandStep2).send()
  }

  @Test
  fun `complete task by error in Camunda`() {
    // GIVEN
    val throwErrorCommandStep1: ThrowErrorCommandStep1 = mock()
    val throwErrorCommandStep2: ThrowErrorCommandStep2 = mock()
    val zeebeFuture: CamundaFuture<ThrowErrorResponse> = mock()

    whenever(camundaClient.newThrowErrorCommand(TASK_ID.toLong())).thenReturn(throwErrorCommandStep1)
    whenever(throwErrorCommandStep1.errorCode(any())).thenReturn(throwErrorCommandStep2)
    whenever(throwErrorCommandStep2.errorMessage(any())).thenReturn(throwErrorCommandStep2)
    whenever(throwErrorCommandStep2.variables(PAYLOAD)).thenReturn(throwErrorCommandStep2)
    whenever(throwErrorCommandStep2.send()).thenReturn(zeebeFuture)

    // WHEN
    taskCompletionApi.completeTaskByError(
      CompleteTaskByErrorCmd(
        taskId = TASK_ID,
        errorCode = ERROR_CODE,
        errorMessage = REASON,
        payloadSupplier = { PAYLOAD }
      )
    )

    // THEN
    verify(throwErrorCommandStep1).errorCode(ERROR_CODE)
    verify(throwErrorCommandStep2).errorMessage(REASON)
    verify(throwErrorCommandStep2).variables(PAYLOAD)
    verify(throwErrorCommandStep2).send()
  }
}
