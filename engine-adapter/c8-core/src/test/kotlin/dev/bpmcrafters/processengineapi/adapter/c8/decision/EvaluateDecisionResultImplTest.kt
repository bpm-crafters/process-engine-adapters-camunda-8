package dev.bpmcrafters.processengineapi.adapter.c8.decision

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EvaluateDecisionResultImplTest {
  @Test
  fun `single value result successfully interpreted as the single value`() {
    val result = EvaluateDecisionResultImpl(jacksonObjectMapper(), "10").asSingle()
    assertNotNull(result)
  }
  @Test
  fun `multi-value result successfully interpreted as the list of vaues`() {
    val result = EvaluateDecisionResultImpl(jacksonObjectMapper(), "[10,11]").asList()
    assertEquals(2, result.size)
  }

  @Test
  fun `multi-value with multiu-outputs successfully interpreted as the list of composite values`() {
    val result = EvaluateDecisionResultImpl(jacksonObjectMapper(), "[{\"price\":10,\"name\":\"perfect offer\"},{\"price\":15,\"name\":\"medium offer\"}]").asList()
    assertEquals(2, result.size)
  }

  @Test
  fun `single-value result casting to the list outputs is to fail`() {
    assertThrows(RuntimeException::class.java) {
      EvaluateDecisionResultImpl(jacksonObjectMapper(), "10").asList()
    }
  }

  @Test
  fun `null literal in result successfully interpreted as an empty list`() {
      val result = EvaluateDecisionResultImpl(jacksonObjectMapper(), "null").asList()
      assertTrue(result.isEmpty())
  }

  @Test
  fun `null successfully interpreted as an empty list`() {
    val result = EvaluateDecisionResultImpl(jacksonObjectMapper(), null).asList()
    assertTrue(result.isEmpty())
  }

  @Test
  fun `null successfully interpreted to single null value `() {
    val result = EvaluateDecisionResultImpl(jacksonObjectMapper(), null).asSingle()
    assertNull(result.asType(Object::class.java))
  }


  @Test
  fun `null literal in result successfully interpreted to single null value `() {
    val result = EvaluateDecisionResultImpl(jacksonObjectMapper(), "null").asSingle()
    assertNull(result.asType(Object::class.java))
  }
}
