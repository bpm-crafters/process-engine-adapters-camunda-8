package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.MetaInfo
import dev.bpmcrafters.processengineapi.MetaInfoAware
import dev.bpmcrafters.processengineapi.deploy.DeployBundleCommand
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.deploy.DeploymentInformation
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Provides a deployment API that delays the deployment by a given duration, in order the engine to adapt to the deployment.
 */
internal class DelayedDeploymentApi(
  private val delegate: DeploymentApi,
  private val delay: Duration,
) : DeploymentApi {
  override fun deploy(cmd: DeployBundleCommand): CompletableFuture<DeploymentInformation> =
    delegate.deploy(cmd).thenApply { deployment ->
      Thread.sleep(delay.toMillis())
      deployment
    }

  override fun meta(instance: MetaInfoAware): MetaInfo = delegate.meta(instance)
}
