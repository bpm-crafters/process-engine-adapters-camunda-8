package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.RefreshableDelivery
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.testing.api.*
import dev.bpmcrafters.processengineapi.testing.config.ProcessTestPollingConfiguration
import io.camunda.client.CamundaClient
import io.camunda.process.test.impl.client.CamundaManagementClient
import io.camunda.process.test.impl.runtime.CamundaProcessTestContainerRuntime
import io.camunda.process.test.impl.runtime.CamundaProcessTestRuntime
import java.time.Duration

/**
 * Provides access to the Camunda 8 process test runtime and its APIs.
 */
internal class C8ProcessTestContext(
  private val qualifier: String,
  private val runtime: CamundaProcessTestRuntime,
  private val client: CamundaClient,
  private val clock: CamundaManagementClient,
  configuration: C8ProcessTestSettings,
) : ProcessTestContext {

  private val subscriptions = InMemSubscriptionRepository()
  private val delivery: RefreshableDelivery = PullUserTaskDelivery(client, subscriptions)
  private val polling = ProcessTestPollingConfiguration(Duration.ofMillis(100), Duration.ofSeconds(15))
  private val runtimeApis = C8RuntimeApis(client, subscriptions, configuration)
  private val query = C8QueryApi(client, delivery)

  companion object {
    fun managed(qualifier: String, configuration: C8ProcessTestSettings): C8ProcessTestContext {
      val runtime = CamundaProcessTestContainerRuntime.newDefaultRuntime()
      runtime.start()
      runtime.waitUntilClusterReady(Duration.ofSeconds(60))
      return C8ProcessTestContext(
        qualifier,
        runtime,
        runtime.camundaClientBuilderFactory.get().build(),
        CamundaManagementClient.createClient(runtime.camundaMonitoringApiAddress),
        configuration,
      )
    }
  }

  override fun qualifier() = qualifier
  override fun runtime() = runtimeApis
  override fun query(): ProcessTestQueryApi = query
  override fun assertions(): ProcessTestAssertApi = C8AssertApi(client, query, polling)
  override fun payloads(): ProcessTestPayloadApi = C8PayloadApi(client)
  override fun capabilities(): ProcessTestCapabilities = object : ProcessTestCapabilities {
    override fun supportsTimeTravel() = true
    override fun timePasses(duration: Duration) = clock.increaseTime(duration)
  }

  override fun diagnostics() = ProcessTestDiagnostics(polling)

  override fun close() {
    client.close()
    runtime.close()
  }

}
