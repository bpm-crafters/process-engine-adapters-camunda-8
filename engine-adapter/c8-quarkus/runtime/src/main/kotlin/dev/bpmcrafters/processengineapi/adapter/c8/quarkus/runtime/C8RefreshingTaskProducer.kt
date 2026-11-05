package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskJobCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import io.camunda.client.CamundaClient
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

@Dependent
class C8RefreshingTaskProducer {

  @Produces
  @Singleton
  @DefaultBean
  fun delivery(
    client: CamundaClient,
    repository: SubscriptionRepository,
    config: C8AdapterRuntimeConfig
  ): SubscribingRefreshingZeebeJobUserTaskDelivery = SubscribingRefreshingZeebeJobUserTaskDelivery(
    client, repository, config.requiredWorkerId(), Math.multiplyExact(config.scheduleIntervalInSeconds(), 2000)
  )

  @Produces
  @Singleton
  @DefaultBean
  fun completion(client: CamundaClient, repository: SubscriptionRepository): UserTaskCompletionApi =
    C8CamundaClientUserTaskJobCompletionApiImpl(client, repository)
}
