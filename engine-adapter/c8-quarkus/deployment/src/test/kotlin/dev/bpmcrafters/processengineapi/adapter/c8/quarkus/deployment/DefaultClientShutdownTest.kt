package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import io.camunda.client.CamundaClient
import io.quarkus.test.QuarkusUnitTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class DefaultClientShutdownTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = ClientLifecycleTestSupport.application()
  }

  @field:Inject lateinit var client: CamundaClient

  @Test
  fun `official client remains usable until natural shutdown`() {
    assertThat(client.configuration.grpcAddress.host).isEqualTo("localhost")
    assertThat(client.newTopologyRequest()).isNotNull()
    // The after-undeploy hook asserts worker-before-client ordering and exactly one close.
  }
}
