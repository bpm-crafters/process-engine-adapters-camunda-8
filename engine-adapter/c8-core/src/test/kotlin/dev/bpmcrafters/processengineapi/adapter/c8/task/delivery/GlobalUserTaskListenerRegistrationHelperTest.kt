package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.ClientHttpException
import io.camunda.client.api.command.ClientStatusException
import io.camunda.client.api.command.CreateGlobalTaskListenerCommandStep1
import io.camunda.client.api.fetch.GlobalTaskListenerGetRequest
import io.camunda.client.api.response.GlobalTaskListenerResponse
import io.camunda.client.api.search.enums.GlobalTaskListenerEventType
import io.camunda.client.api.search.response.GlobalTaskListener
import io.grpc.Status
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

internal class GlobalUserTaskListenerRegistrationHelperTest {

  companion object {
    const val LISTENER_ID = "process-engine-user-tasks"
    const val TOPIC = "process-engine-user-tasks"
  }

  private val camundaClient: CamundaClient = mock()

  @Test
  fun `should not register global listener when disabled`() {
    val registration = GlobalUserTaskListenerRegistrationHelper(
      camundaClient = camundaClient,
      autoRegisterGlobalListener = false,
      globalListenerId = LISTENER_ID,
      topic = TOPIC,
      globalListenerRetries = 3,
      globalListenerAfterNonGlobal = true,
      globalListenerPriority = 50
    )

    registration.registerIfEnabled()

    verify(camundaClient, never()).newGlobalTaskListenerGetRequest(any())
    verify(camundaClient, never()).newCreateGlobalTaskListenerRequest()
  }

  @Test
  fun `should not create global listener when matching listener already exists`() {
    val getRequest = mock<GlobalTaskListenerGetRequest>()
    val getFuture = mock<CamundaFuture<GlobalTaskListener>>()
    val existingListener = matchingGlobalListener()
    whenever(camundaClient.newGlobalTaskListenerGetRequest(LISTENER_ID)).thenReturn(getRequest)
    whenever(getRequest.send()).thenReturn(getFuture)
    whenever(getFuture.join()).thenReturn(existingListener)
    val registration = GlobalUserTaskListenerRegistrationHelper(
      camundaClient = camundaClient,
      autoRegisterGlobalListener = true,
      globalListenerId = LISTENER_ID,
      topic = TOPIC,
      globalListenerRetries = 3,
      globalListenerAfterNonGlobal = true,
      globalListenerPriority = 50
    )

    registration.registerIfEnabled()

    verify(camundaClient, never()).newCreateGlobalTaskListenerRequest()
  }

  @Test
  fun `should create global listener when it is absent`() {
    val getRequest = mock<GlobalTaskListenerGetRequest>()
    val getFuture = mock<CamundaFuture<GlobalTaskListener>>()
    val createStep1 = mock<CreateGlobalTaskListenerCommandStep1>()
    val createStep2 = mock<CreateGlobalTaskListenerCommandStep1.CreateGlobalTaskListenerCommandStep2>()
    val createStep3 = mock<CreateGlobalTaskListenerCommandStep1.CreateGlobalTaskListenerCommandStep3>()
    val createStep4 = mock<CreateGlobalTaskListenerCommandStep1.CreateGlobalTaskListenerCommandStep4>()
    val createFuture = mock<CamundaFuture<GlobalTaskListenerResponse>>()
    whenever(camundaClient.newGlobalTaskListenerGetRequest(LISTENER_ID)).thenReturn(getRequest)
    whenever(getRequest.send()).thenReturn(getFuture)
    whenever(getFuture.join()).thenThrow(ClientHttpException(404, "missing"))
    whenever(camundaClient.newCreateGlobalTaskListenerRequest()).thenReturn(createStep1)
    whenever(createStep1.id(LISTENER_ID)).thenReturn(createStep2)
    whenever(createStep2.type(TOPIC)).thenReturn(createStep3)
    whenever(createStep3.eventTypes(listOf(GlobalTaskListenerEventType.ALL))).thenReturn(createStep4)
    whenever(createStep4.retries(3)).thenReturn(createStep4)
    whenever(createStep4.afterNonGlobal(true)).thenReturn(createStep4)
    whenever(createStep4.priority(50)).thenReturn(createStep4)
    whenever(createStep4.send()).thenReturn(createFuture)
    val registration = GlobalUserTaskListenerRegistrationHelper(
      camundaClient = camundaClient,
      autoRegisterGlobalListener = true,
      globalListenerId = LISTENER_ID,
      topic = TOPIC,
      globalListenerRetries = 3,
      globalListenerAfterNonGlobal = true,
      globalListenerPriority = 50
    )

    registration.registerIfEnabled()

    verify(createStep1).id(LISTENER_ID)
    verify(createStep2).type(TOPIC)
    verify(createStep3).eventTypes(listOf(GlobalTaskListenerEventType.ALL))
    verify(createStep4).retries(3)
    verify(createStep4).afterNonGlobal(true)
    verify(createStep4).priority(50)
    verify(createStep4).send()
  }

  @Test
  fun `should surface global listener registration failures`() {
    val getRequest = mock<GlobalTaskListenerGetRequest>()
    val getFuture = mock<CamundaFuture<GlobalTaskListener>>()
    whenever(camundaClient.newGlobalTaskListenerGetRequest(LISTENER_ID)).thenReturn(getRequest)
    whenever(getRequest.send()).thenReturn(getFuture)
    whenever(getFuture.join()).thenThrow(
      ClientStatusException(
        Status.PERMISSION_DENIED.withDescription("denied"),
        null
      )
    )
    val registration = GlobalUserTaskListenerRegistrationHelper(
      camundaClient = camundaClient,
      autoRegisterGlobalListener = true,
      globalListenerId = LISTENER_ID,
      topic = TOPIC,
      globalListenerRetries = 3,
      globalListenerAfterNonGlobal = true,
      globalListenerPriority = 50

    )

    Assertions.assertThatThrownBy {
      registration.registerIfEnabled()
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("Failed to auto-register Camunda global user task listener '$LISTENER_ID'")
      .hasMessageContaining("permissions")
  }

  private fun matchingGlobalListener(): GlobalTaskListener =
    mock {
      whenever(it.id).thenReturn(LISTENER_ID)
      whenever(it.type).thenReturn(TOPIC)
      whenever(it.eventTypes).thenReturn(listOf(GlobalTaskListenerEventType.ALL))
      whenever(it.retries).thenReturn(3)
      whenever(it.afterNonGlobal).thenReturn(true)
      whenever(it.priority).thenReturn(0)
    }

}
