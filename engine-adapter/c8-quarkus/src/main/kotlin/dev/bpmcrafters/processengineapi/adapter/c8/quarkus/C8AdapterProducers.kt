package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.correlation.CorrelationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.correlation.SignalApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.decision.EvaluateDecisionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.deploy.DeploymentApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.process.StartProcessApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.subscription.C8TaskSubscriptionApiImpl
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi
import dev.bpmcrafters.processengineapi.correlation.SignalApi
import dev.bpmcrafters.processengineapi.decision.EvaluateDecisionApi
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.process.StartProcessApi
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import io.camunda.client.CamundaClient
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Produces the process engine api beans, mirroring `C8AdapterAutoConfiguration` of the Spring Boot
 * starter. The [CamundaClient] is expected to be provided by the application, e.g. via the
 * `io.quarkiverse.camunda:quarkus-camunda` extension. All beans are lazy: if the adapter is
 * disabled, using one of them fails fast with a clear message.
 */
@Singleton
class C8AdapterProducers {

  @Produces
  @ApplicationScoped
  fun startProcessApi(camundaClient: CamundaClient, properties: C8AdapterProperties): StartProcessApi {
    properties.requireEnabled()
    return StartProcessApiImpl(
      camundaClient = camundaClient
    )
  }

  /**
   * The core api takes a nullable delivery and only uses it to close jobs on unsubscribe; the
   * `SCHEDULED` and `CUSTOM` strategies have none, exactly as in the Spring Boot starter.
   */
  @Produces
  @ApplicationScoped
  fun taskSubscriptionApi(
    subscriptionRepository: SubscriptionRepository,
    subscribingUserTaskDelivery: Instance<SubscribingUserTaskDelivery>,
    properties: C8AdapterProperties
  ): TaskSubscriptionApi {
    properties.requireEnabled()
    return C8TaskSubscriptionApiImpl(
      subscriptionRepository = subscriptionRepository,
      subscribingUserTaskDelivery = subscribingUserTaskDelivery.takeIf { it.isResolvable }?.get()
    )
  }

  @Produces
  @ApplicationScoped
  fun correlationApi(camundaClient: CamundaClient, properties: C8AdapterProperties): CorrelationApi {
    properties.requireEnabled()
    return CorrelationApiImpl(
      camundaClient = camundaClient
    )
  }

  @Produces
  @ApplicationScoped
  fun signalApi(camundaClient: CamundaClient, properties: C8AdapterProperties): SignalApi {
    properties.requireEnabled()
    return SignalApiImpl(
      camundaClient = camundaClient
    )
  }

  @Produces
  @ApplicationScoped
  fun deploymentApi(camundaClient: CamundaClient, properties: C8AdapterProperties): DeploymentApi {
    properties.requireEnabled()
    return DeploymentApiImpl(
      camundaClient = camundaClient
    )
  }

  @Produces
  @ApplicationScoped
  @DefaultBean
  fun evaluateDecisionApi(camundaClient: CamundaClient, properties: C8AdapterProperties): EvaluateDecisionApi {
    properties.requireEnabled()
    return EvaluateDecisionApiImpl(
      camundaClient = camundaClient
    )
  }

  @Produces
  @ApplicationScoped
  @DefaultBean
  fun subscriptionRepository(): SubscriptionRepository = InMemSubscriptionRepository()
}
