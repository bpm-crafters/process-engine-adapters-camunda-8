package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import dev.bpmcrafters.processengineapi.adapter.c8.correlation.CorrelationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.correlation.SignalApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.decision.EvaluateDecisionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.deploy.DeploymentApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.process.StartProcessApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8ExternalServiceTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.FailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.LinearMemoryFailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.modification.C8CamundaClientUserTaskModificationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.subscription.C8TaskSubscriptionApiImpl
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi
import dev.bpmcrafters.processengineapi.correlation.SignalApi
import dev.bpmcrafters.processengineapi.decision.EvaluateDecisionApi
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.process.StartProcessApi
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.UserTaskModificationApi
import io.camunda.client.CamundaClient
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

@Dependent
class C8AdapterProducer {

  @Produces
  @Singleton
  @DefaultBean
  fun deploymentApi(client: CamundaClient): DeploymentApi = DeploymentApiImpl(client)

  @Produces
  @Singleton
  @DefaultBean
  fun startProcessApi(client: CamundaClient): StartProcessApi = StartProcessApiImpl(client)

  @Produces
  @Singleton
  @DefaultBean
  fun correlationApi(client: CamundaClient): CorrelationApi = CorrelationApiImpl(client)

  @Produces
  @Singleton
  @DefaultBean
  fun signalApi(client: CamundaClient): SignalApi = SignalApiImpl(client)

  @Produces
  @Singleton
  @DefaultBean
  fun evaluateDecisionApi(client: CamundaClient): EvaluateDecisionApi = EvaluateDecisionApiImpl(client)

  @Produces
  @Singleton
  @DefaultBean
  fun subscriptionRepository(): SubscriptionRepository = InMemSubscriptionRepository()

  @Produces
  @Singleton
  @DefaultBean
  fun failureRetrySupplier(config: C8AdapterRuntimeConfig): FailureRetrySupplier =
    LinearMemoryFailureRetrySupplier(config.serviceTasks().retries(), config.serviceTasks().retryTimeoutInSeconds())

  @Produces
  @Singleton
  @DefaultBean
  fun taskSubscriptionApi(
    repository: SubscriptionRepository,
    delivery: Instance<SubscribingUserTaskDelivery>
  ): TaskSubscriptionApi = C8TaskSubscriptionApiImpl(repository, if (delivery.isUnsatisfied) null else delivery.get())

  @Produces
  @Singleton
  @DefaultBean
  fun serviceTaskCompletionApi(
    client: CamundaClient,
    repository: SubscriptionRepository,
    retrySupplier: FailureRetrySupplier
  ): ServiceTaskCompletionApi = C8ExternalServiceTaskCompletionApiImpl(client, repository, retrySupplier)

  @Produces
  @Singleton
  @DefaultBean
  fun userTaskModificationApi(client: CamundaClient): UserTaskModificationApi =
    C8CamundaClientUserTaskModificationApiImpl(client)
}
