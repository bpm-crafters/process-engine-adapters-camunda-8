package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.it;

import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskCompletionApiImpl;
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery;
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;
import io.camunda.client.CamundaClient;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
@IfBuildProperty(name = TaskHistory.STRATEGY, stringValue = "CUSTOM")
public class CustomUserTasks {
  @Produces
  @Singleton
  UserTaskCompletionApi completion(final CamundaClient client, final SubscriptionRepository repository) {
    return new C8CamundaClientUserTaskCompletionApiImpl(client, repository);
  }

  @Produces
  @Singleton
  PullUserTaskDelivery delivery(final CamundaClient client, final SubscriptionRepository repository) {
    return new PullUserTaskDelivery(client, repository);
  }
}
