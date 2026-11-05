package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.deploy.DeploymentApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.process.StartProcessApiImpl
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.process.StartProcessApi
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
      .overrideConfigKey("quarkus.camunda.devservices.enabled", "false")
      .overrideConfigKey("quarkus.camunda.resources.enabled", "false")
      .overrideRuntimeConfigKey("quarkus.camunda.active", "false")
  }

  @field:Inject
  lateinit var deploymentApi: DeploymentApi

  @field:Inject
  lateinit var startProcessApi: StartProcessApi

  @Test
  fun `adapter uses core implementations`() {
    assertThat(deploymentApi).isInstanceOf(DeploymentApiImpl::class.java)
    assertThat(startProcessApi).isInstanceOf(StartProcessApiImpl::class.java)
  }
}
