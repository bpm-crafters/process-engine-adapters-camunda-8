package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.adapter.c8.correlation.CorrelationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.correlation.SignalApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.deploy.DeploymentApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.process.StartProcessApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8ExternalServiceTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.LinearMemoryFailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.modification.C8CamundaClientUserTaskModificationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.subscription.C8TaskSubscriptionApiImpl
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi
import dev.bpmcrafters.processengineapi.correlation.SignalApi
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.process.StartProcessApi
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.UserTaskModificationApi
import dev.bpmcrafters.processengineapi.testing.api.ProcessTestRuntimeApis
import io.camunda.client.CamundaClient

/**
 * Provides the runtime APIs for the C8 process engine adapter.
 */
internal class C8RuntimeApis(
  client: CamundaClient,
  subscriptions: InMemSubscriptionRepository,
  configuration: C8ProcessTestSettings,
) : ProcessTestRuntimeApis {
  private val start = StartProcessApiImpl(client)
  private val tasks = C8TaskSubscriptionApiImpl(subscriptions, null)
  private val userCompletion = C8CamundaClientUserTaskCompletionApiImpl(client, subscriptions)
  private val userModification = C8CamundaClientUserTaskModificationApiImpl(client)
  private val serviceCompletion = C8ExternalServiceTaskCompletionApiImpl(client, subscriptions, LinearMemoryFailureRetrySupplier(3, 1))
  private val correlation = CorrelationApiImpl(client)
  private val signal = SignalApiImpl(client)
  private val deployment = DelayedDeploymentApi(DeploymentApiImpl(client), configuration.deploymentPropagationDelay)

  override fun startProcessApi(): StartProcessApi = start
  override fun taskSubscriptionApi(): TaskSubscriptionApi = tasks
  override fun userTaskCompletionApi(): UserTaskCompletionApi = userCompletion
  override fun userTaskModificationApi(): UserTaskModificationApi = userModification
  override fun serviceTaskCompletionApi(): ServiceTaskCompletionApi = serviceCompletion
  override fun correlationApi(): CorrelationApi = correlation
  override fun signalApi(): SignalApi = signal
  override fun deploymentApi(): DeploymentApi = deployment
}
