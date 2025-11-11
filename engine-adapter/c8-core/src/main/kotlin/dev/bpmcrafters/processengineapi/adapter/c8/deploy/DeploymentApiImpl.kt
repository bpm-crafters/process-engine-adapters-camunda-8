package dev.bpmcrafters.processengineapi.adapter.c8.deploy

import dev.bpmcrafters.processengineapi.MetaInfo
import dev.bpmcrafters.processengineapi.MetaInfoAware
import dev.bpmcrafters.processengineapi.deploy.DeployBundleCommand
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.deploy.DeploymentInformation
import io.camunda.client.CamundaClient
import io.camunda.client.api.response.DeploymentEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger {}

class DeploymentApiImpl(
  private val camundaClient: CamundaClient
) : DeploymentApi {

  override fun deploy(cmd: DeployBundleCommand): CompletableFuture<DeploymentInformation> {
    require(cmd.resources.isNotEmpty()) { "Resources must not be empty, at least one resource must be provided." }
    logger.debug { "PROCESS-ENGINE-C8-003: Executing a bundle deployment with ${cmd.resources.size} resources." }
    val first = cmd.resources.first()
    return CompletableFuture.supplyAsync {
      camundaClient
        .newDeployResourceCommand()
        .addResourceStream(first.resourceStream, first.name)
        .apply {
          if (cmd.resources.size > 1) {
            val remaining = cmd.resources.subList(1, cmd.resources.size)
            remaining.forEach { resource -> this.addResourceStream(resource.resourceStream, resource.name) }
          }
          if (!cmd.tenantId.isNullOrBlank()) {
            this.tenantId(cmd.tenantId)
          }
        }
        .send()
        .get()
        .toDeploymentInformation()
    }
  }

  override fun meta(instance: MetaInfoAware): MetaInfo {
    TODO("Not yet implemented")
  }

  private fun DeploymentEvent.toDeploymentInformation() = DeploymentInformation(
    deploymentKey = "${this.key}",
    deploymentTime = null,
    tenantId = this.tenantId
  )
}
