package dev.bpmcrafters.processengineapi.adapter.c8.process

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.process.StartProcessByDefinitionAtElementCmd
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.CreateProcessInstanceCommandStep1
import io.camunda.client.api.command.CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep2
import io.camunda.client.api.command.CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3
import io.camunda.client.api.response.ProcessInstanceEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StartProcessApiImplTest {

  companion object {
    const val DEFINITION_KEY = "simple-process"
    const val ELEMENT_ID = "user-perform-task"
    const val TENANT_ID = "tenant-1"
    const val PROCESS_INSTANCE_KEY = 12345L
    const val PROCESS_DEFINITION_KEY = 67890L
  }

  private val camundaClient: CamundaClient = mockk(relaxUnitFun = true)
  private val startProcessApi = StartProcessApiImpl(camundaClient)

  @Test
  fun `should start process at element with tenant and payload`() {
    // GIVEN
    val payload = mapOf("orderNumber" to "12345", "amount" to 100)
    val createCommandStep1: CreateProcessInstanceCommandStep1 = mockk()
    val createCommandStep2: CreateProcessInstanceCommandStep2 = mockk()
    val createCommandStep3: CreateProcessInstanceCommandStep3 = mockk()
    val camundaFuture: CamundaFuture<ProcessInstanceEvent> = mockk(relaxed = true)
    val processInstanceEvent: ProcessInstanceEvent = mockk()

    every { camundaClient.newCreateInstanceCommand() } returns createCommandStep1
    every { createCommandStep1.bpmnProcessId(DEFINITION_KEY) } returns createCommandStep2
    every { createCommandStep2.latestVersion() } returns createCommandStep3
    every { createCommandStep3.variables(payload) } returns createCommandStep3
    every { createCommandStep3.tenantId(TENANT_ID) } returns createCommandStep3
    every { createCommandStep3.startBeforeElement(ELEMENT_ID) } returns createCommandStep3
    every { createCommandStep3.send() } returns camundaFuture
    every { camundaFuture.get() } returns processInstanceEvent

    every { processInstanceEvent.processInstanceKey } returns PROCESS_INSTANCE_KEY
    every { processInstanceEvent.processDefinitionKey } returns PROCESS_DEFINITION_KEY
    every { processInstanceEvent.bpmnProcessId } returns DEFINITION_KEY
    every { processInstanceEvent.tenantId } returns TENANT_ID

    // WHEN
    val result = startProcessApi.startProcess(
      StartProcessByDefinitionAtElementCmd(
        definitionKey = DEFINITION_KEY,
        elementId = ELEMENT_ID,
        payloadSupplier = { payload },
        restrictions = mapOf(CommonRestrictions.TENANT_ID to TENANT_ID)
      )
    ).get()

    // THEN - Verify the correct API call sequence
    verify { camundaClient.newCreateInstanceCommand() }
    verify { createCommandStep1.bpmnProcessId(DEFINITION_KEY) }
    verify { createCommandStep2.latestVersion() }
    verify { createCommandStep3.variables(payload) }
    verify { createCommandStep3.tenantId(TENANT_ID) }
    verify { createCommandStep3.startBeforeElement(ELEMENT_ID) }
    verify { createCommandStep3.send() }

    // AND - Verify result mapping
    assertThat(result.instanceId).isEqualTo(PROCESS_INSTANCE_KEY.toString())
    assertThat(result.meta[CommonRestrictions.PROCESS_DEFINITION_KEY]).isEqualTo(DEFINITION_KEY)
    assertThat(result.meta[CommonRestrictions.TENANT_ID]).isEqualTo(TENANT_ID)
    assertThat(result.meta["processDefinitionId"]).isEqualTo(PROCESS_DEFINITION_KEY.toString())
  }
}
