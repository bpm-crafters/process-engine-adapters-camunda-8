package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.deploy.DeploymentApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.process.StartProcessApiImpl
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.process.StartProcessApi
import io.camunda.client.CamundaClient
import io.quarkus.arc.Arc
import io.quarkus.test.QuarkusUnitTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class EnabledAdapterTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = QuarkusUnitTest()
      .withEmptyApplication()
      .overrideConfigKey("quarkus.process-engine-api.c8.enabled", "true")
      .overrideConfigKey("quarkus.process-engine-api.c8.service-tasks.delivery-strategy", "CUSTOM")
      .overrideConfigKey("quarkus.process-engine-api.c8.user-tasks.delivery-strategy", "CUSTOM")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.service-tasks.worker-id", "test-worker")
  }

  @field:Inject
  lateinit var deploymentApi: DeploymentApi

  @field:Inject
  lateinit var startProcessApi: StartProcessApi

  @Test
  fun `adapter uses core implementations`() {
    assertThat(deploymentApi).isInstanceOf(DeploymentApiImpl::class.java)
    assertThat(startProcessApi).isInstanceOf(StartProcessApiImpl::class.java)
    val clients = Arc.container().select(CamundaClient::class.java)
    assertThat(clients.stream().count()).isEqualTo(1)
    assertThat(clients.get()).isSameAs(clients.get())
    assertThat(clients.get().configuration.grpcAddress.toString()).isEqualTo("http://localhost:26500")
  }
}
