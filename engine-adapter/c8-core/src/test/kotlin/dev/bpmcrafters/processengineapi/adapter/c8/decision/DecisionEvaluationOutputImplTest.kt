package dev.bpmcrafters.processengineapi.adapter.c8.decision

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class DecisionEvaluationOutputImplTest {
  companion object {
    val objectMapper = jacksonObjectMapper()
  }
  @Test
  fun `cast null literal to Map successfully with null result`()  {
    assertNull(DecisionEvaluationOutputImpl(objectMapper, "null").asMap())
  }

  @Test
  fun `cast multi-component output to Map successfully`()  {
    val result = DecisionEvaluationOutputImpl(objectMapper, "{\"price\":12,\"name\":\"perfect offer\"}").asMap()
    assertNotNull(result)
    assertEquals(result!!["price"], 12)
    assertEquals(result["name"], "perfect offer")
  }

  @Test
  fun `cast single-value output to Map fails`()  {
    assertFailsWith<RuntimeException> {
      DecisionEvaluationOutputImpl(objectMapper, "10").asMap()
    }
  }

  @Test
  fun `cast single-value output to matching asType(Double) successfully`() {
    val result = DecisionEvaluationOutputImpl(objectMapper, "10.1").asType(Double::class.java)
    assertEquals(result, 10.1)
  }

  @Test
  fun `cast null output asType successfully into complex type`() {
    val result = DecisionEvaluationOutputImpl(objectMapper, "null").asType(Offer::class.java)
    assertNull(result)
  }

  @Test
  fun `cast null output asType successfully into Double with null value, instead of default 0 value`() {
    val result = DecisionEvaluationOutputImpl(objectMapper, "null").asType(Double::class.java)
    assertNull(result)
  }

  @Test
  fun `cast multi-component output to complex asType(Offer) successfully`() {
    val result = DecisionEvaluationOutputImpl(objectMapper, "{\"price\":12,\"name\":\"perfect offer\"}").asType(Offer::class.java)
    assertNotNull(result)
    assertEquals(result!!.price, 12)
    assertEquals(result.name, "perfect offer")
  }

  data class Offer(val price: Int, val name: String)


}
