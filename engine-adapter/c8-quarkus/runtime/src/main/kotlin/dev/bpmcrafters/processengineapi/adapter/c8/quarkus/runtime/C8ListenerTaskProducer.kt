package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import io.camunda.client.CamundaClient
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

@Dependent
class C8ListenerTaskProducer {

  @Produces
  @Singleton
  @DefaultBean
  fun delivery(
    client: CamundaClient,
    repository: SubscriptionRepository,
    config: C8AdapterRuntimeConfig
  ): ListenerUserTaskDelivery {
    val listener = config.userTasks().listener()
    return ListenerUserTaskDelivery(
      client, repository, listener.topic(), listener.workerId(), listener.maxJobsActive(), listener.streamEnabled(),
      listener.lockTimeInSeconds(), listener.retryTimeoutInSeconds()
    )
  }

  @Produces
  @Singleton
  @DefaultBean
  fun registration(client: CamundaClient, config: C8AdapterRuntimeConfig): GlobalUserTaskListenerRegistrationHelper {
    val listener = config.userTasks().listener()
    return GlobalUserTaskListenerRegistrationHelper(
      client, listener.autoRegisterGlobalListener(), listener.globalListenerId(), listener.topic(),
      listener.globalListenerRetries(), listener.globalListenerAfterNonGlobal(), listener.globalListenerPriority()
    )
  }
}
