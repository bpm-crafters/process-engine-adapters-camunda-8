package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import io.camunda.client.CamundaClient
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

@Dependent
class C8ServiceTaskProducer {

  @Produces
  @Singleton
  @DefaultBean
  fun delivery(
    client: CamundaClient,
    repository: SubscriptionRepository,
    config: C8AdapterRuntimeConfig
  ): SubscribingServiceTaskDelivery {
    val service = config.serviceTasks()
    return SubscribingServiceTaskDelivery(
      client, repository, config.requiredWorkerId(), service.retryTimeoutInSeconds(), service.lockTimeInSeconds()
    )
  }
}
