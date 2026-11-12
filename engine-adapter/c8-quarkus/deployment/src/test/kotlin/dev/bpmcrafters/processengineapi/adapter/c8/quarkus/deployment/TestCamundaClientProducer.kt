package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import io.camunda.client.CamundaClient
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Singleton
class TestCamundaClientProducer {
  @Produces
  @Singleton
  fun client(): CamundaClient {
    val client = mock<CamundaClient>()
    val first = mock<JobWorkerBuilderStep1>()
    val second = mock<JobWorkerBuilderStep2>()
    val third = mock<JobWorkerBuilderStep3>(defaultAnswer = RETURNS_SELF)
    whenever(client.newWorker()).thenReturn(first)
    whenever(first.jobType(any())).thenReturn(second)
    whenever(second.handler(any())).thenReturn(third)
    whenever(third.open()).thenReturn(mock<JobWorker>())
    return client
  }
}
