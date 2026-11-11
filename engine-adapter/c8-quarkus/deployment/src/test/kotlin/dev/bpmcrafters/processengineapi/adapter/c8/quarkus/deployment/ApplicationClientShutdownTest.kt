package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import io.camunda.client.CamundaClient
import io.quarkus.test.QuarkusUnitTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class ApplicationClientShutdownTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = ClientLifecycleTestSupport.application(supplied = true)
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.request-timeout", "not-a-duration")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.prefer-rest-over-grpc", "not-a-boolean")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.grpc-address", "")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.rest-address", "")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.oauth.auth-url", "")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.oauth.client-id", "unused-partial-oauth")
  }

  @field:Inject lateinit var client: CamundaClient

  @Test
  fun `only application disposer closes supplied client on natural teardown`() {
    verify(client, never()).close()
    // The application disposer checks ownership; after-undeploy checks worker-before-client ordering.
  }
}
