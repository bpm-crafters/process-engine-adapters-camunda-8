package dev.bpmcrafters.processengineapi.adapter.c8.decision

import dev.bpmcrafters.processengineapi.decision.DecisionByRefEvaluationCommand
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.EvaluateDecisionCommandStep1
import io.camunda.client.api.response.EvaluateDecisionResponse
import io.camunda.client.impl.CamundaObjectMapper
import io.camunda.client.impl.response.EvaluateDecisionResponseImpl
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.testcontainers.shaded.org.bouncycastle.util.encoders.Base64
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CompletionException

class EvaluateDecisionApiImplTest {

  private val camundaClient: CamundaClient = mockk(relaxUnitFun = true)
  private val api = EvaluateDecisionApiImpl(camundaClient)

  @Test
  fun `evaluate decision by ref - success`() {
    // GIVEN
    val decisionRef = "CollectMOutputDecision"
    val payload = mapOf("id" to 199, "amount" to 5000)

    mockDecisionCommandVerifyAndReturn(payload, decisionRef, "decision/responses/success_response.pbuf")

    // WHEN
    val result = api.evaluateDecision(
      DecisionByRefEvaluationCommand(decisionRef, payload, emptyMap())
    ).join()


    // result mapping assertions
    assertNotNull(result)
    val mappedResult = result.asList().map { it.asType(Score::class.java) }.toList()
    assertEquals(2, mappedResult.size)
    val meta = result.meta()
    assertEquals("CollectMOutputDecision", meta["decisionDefinitionId"])
    assertEquals("14", meta["decisionDefinitionVersion"])
    assertEquals("2251799813703261", meta["decisionDefinitionKey"])
    assertEquals("Collect MOutput Decision", meta["decisionDefinitionName"])
    assertEquals("MainDecision", meta["decisionRequirementsId"])
    assertEquals("2251799813703258", meta["decisionRequirementsKey"])
    assertEquals("<default>", meta["tenantId"]) // CommonRestrictions.TENANT_ID
    assertEquals("", meta["failedDecisionId"])
    assertEquals("", meta["failureMessage"])
  }

  data class Score(val score: Double, val message: String)

  @Test
  fun `evaluate decision by ref with engine return failure in the response -  failure throws`() {
    // GIVEN
    val decisionRef = "my-decision"
    val payload = emptyMap<String, Any>()

    mockDecisionCommandVerifyAndReturn(payload, decisionRef, "decision/responses/failed_response.pbuf")

    // WHEN / THEN
    val ex = assertThrows(CompletionException::class.java) {
      api.evaluateDecision(DecisionByRefEvaluationCommand(decisionRef, payload, emptyMap())).join()
    }
    assertTrue(ex.cause is IOException)
  }

  fun mockDecisionCommandVerifyAndReturn(expectedInputs: Map<String, Any>, expectedDecisionId: String, expectedResponseBase64ClassPath: String) {
    val step1: EvaluateDecisionCommandStep1 = mockk()
    val step2: EvaluateDecisionCommandStep1.EvaluateDecisionCommandStep2 = mockk()
    val future: CamundaFuture<EvaluateDecisionResponse> = mockk()

    every { camundaClient.newEvaluateDecisionCommand() } returns step1
    every { step1.decisionId(expectedDecisionId) } returns step2
    every { step2.variables(expectedInputs) } returns step2
    every { step2.send() } returns future
    every { future.get() } returns
      run {
        val grpcResponse1 = GatewayOuterClass.EvaluateDecisionResponse.parseFrom(
          ByteArrayInputStream(
            Base64.decode(
              ClassPathResource(expectedResponseBase64ClassPath).file.readText(Charsets.UTF_8)
            )
          )
        )
        EvaluateDecisionResponseImpl(CamundaObjectMapper(), grpcResponse1)
      }
  }
}
