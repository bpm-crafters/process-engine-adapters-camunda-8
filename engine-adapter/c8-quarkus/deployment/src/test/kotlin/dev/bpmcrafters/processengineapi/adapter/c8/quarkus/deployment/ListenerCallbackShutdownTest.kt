package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ListenerCallbackShutdownTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION = UserCallbackShutdownTestSupport.application(listener = true)
  }

  @field:Inject lateinit var probe: UserShutdownProbe

  @Test
  fun `natural teardown drains the real listener before application and client disposal`() {
    probe.startCallback()
  }
}
