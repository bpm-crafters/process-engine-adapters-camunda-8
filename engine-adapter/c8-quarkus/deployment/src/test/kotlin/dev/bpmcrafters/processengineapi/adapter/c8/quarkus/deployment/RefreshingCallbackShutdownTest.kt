package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class RefreshingCallbackShutdownTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION = UserCallbackShutdownTestSupport.application(listener = false)
  }

  @field:Inject lateinit var probe: UserShutdownProbe

  @Test
  fun `natural teardown drains the real refreshing delivery before application and client disposal`() {
    probe.startCallback()
  }
}
