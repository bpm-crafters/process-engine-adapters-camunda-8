package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.Companion.DEFAULT_PREFIX
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.UserTaskDeliveryStrategy
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskJobCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8ExternalServiceTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.FailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.LinearMemoryFailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.modification.C8CamundaClientUserTaskModificationApiImpl
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.UserTaskModificationApi
import io.camunda.client.CamundaClient
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Produces the task completion and modification api beans, mirroring
 * `C8CamundaClientAutoConfiguration` of the Spring Boot starter. The strategy-conditional beans of
 * the Spring Boot starter are replaced by a runtime switch on the configured delivery strategy.
 * All producers are default beans, an application-provided bean of the same type takes precedence.
 */
@Singleton
class C8CamundaClientProducers {

  /**
   * Fallback client producer keeping the build-time bean resolution satisfied when no real
   * [CamundaClient] bean is present. The producer body only runs on first actual client usage,
   * turning a missing client into an actionable runtime error instead of a cryptic ArC build
   * failure. A client provided by the quarkus-camunda extension or the application takes
   * precedence.
   */
  @Produces
  @ApplicationScoped
  @DefaultBean
  fun missingCamundaClient(): CamundaClient = throw IllegalStateException(
    "No CamundaClient bean found. Add the io.quarkiverse.camunda:quarkus-camunda extension or provide an own CamundaClient bean."
  )

  @Produces
  @ApplicationScoped
  @DefaultBean
  fun failureRetrySupplier(properties: C8AdapterProperties): FailureRetrySupplier {
    properties.requireEnabled()
    return LinearMemoryFailureRetrySupplier(
      retry = properties.serviceTasks().retries(),
      retryTimeout = properties.serviceTasks().retryTimeoutInSeconds()
    )
  }

  @Produces
  @ApplicationScoped
  @DefaultBean
  fun serviceTaskCompletionApi(
    camundaClient: CamundaClient,
    subscriptionRepository: SubscriptionRepository,
    failureRetrySupplier: FailureRetrySupplier,
    properties: C8AdapterProperties
  ): ServiceTaskCompletionApi {
    properties.requireEnabled()
    return C8ExternalServiceTaskCompletionApiImpl(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      failureRetrySupplier = failureRetrySupplier
    )
  }

  @Produces
  @ApplicationScoped
  @DefaultBean
  fun userTaskCompletionApi(
    camundaClient: CamundaClient,
    subscriptionRepository: SubscriptionRepository,
    properties: C8AdapterProperties
  ): UserTaskCompletionApi {
    properties.requireEnabled()
    return when (properties.requiredUserTaskDeliveryStrategy()) {
      UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING -> C8CamundaClientUserTaskJobCompletionApiImpl(
        camundaClient = camundaClient,
        subscriptionRepository = subscriptionRepository
      )

      UserTaskDeliveryStrategy.SCHEDULED, UserTaskDeliveryStrategy.LISTENER -> C8CamundaClientUserTaskCompletionApiImpl(
        camundaClient = camundaClient,
        subscriptionRepository = subscriptionRepository
      )

      UserTaskDeliveryStrategy.CUSTOM -> throw IllegalStateException(
        "'$DEFAULT_PREFIX.user-tasks.delivery-strategy' is set to CUSTOM. Provide an application-scoped UserTaskCompletionApi bean or choose a different strategy."
      )
    }
  }

  @Produces
  @ApplicationScoped
  @DefaultBean
  fun userTaskModificationApi(camundaClient: CamundaClient, properties: C8AdapterProperties): UserTaskModificationApi {
    properties.requireEnabled()
    return C8CamundaClientUserTaskModificationApiImpl(
      camundaClient = camundaClient
    )
  }
}
