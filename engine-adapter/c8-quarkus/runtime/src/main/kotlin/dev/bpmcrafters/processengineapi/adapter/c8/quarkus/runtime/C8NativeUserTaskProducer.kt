package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import io.camunda.client.CamundaClient
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

@Dependent
class C8NativeUserTaskProducer {

  @Produces
  @Singleton
  @DefaultBean
  fun delivery(client: CamundaClient, repository: SubscriptionRepository): PullUserTaskDelivery =
    PullUserTaskDelivery(client, repository)

  @Produces
  @Singleton
  @DefaultBean
  fun completion(client: CamundaClient, repository: SubscriptionRepository): UserTaskCompletionApi =
    C8CamundaClientUserTaskCompletionApiImpl(client, repository)
}
