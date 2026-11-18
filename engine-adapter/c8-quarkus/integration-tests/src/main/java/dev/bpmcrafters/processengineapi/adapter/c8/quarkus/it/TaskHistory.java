package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.it;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.*;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class TaskHistory {
  public static final String STRATEGY = "quarkus.process-engine-api.c8.user-tasks.delivery-strategy";
  private final List<Event> events = new CopyOnWriteArrayList<>();
  @Inject TaskSubscriptionApi subscriptions;
  private TaskSubscription userSubscription;

  void start(@Observes final StartupEvent event) {
    for (final var topic : List.of("execute-action-external", "quarkus-message-observed",
        "quarkus-signal-observed", "quarkus-service-error")) {
      subscribe(TaskType.EXTERNAL, topic, Map.of());
    }
    final var element = strategy().equals("SUBSCRIPTION_REFRESHING")
        ? "user-perform-task-job-based" : "user-perform-task";
    userSubscription = subscribe(TaskType.USER, element,
        Map.of(CommonRestrictions.PROCESS_DEFINITION_KEY, "simple-process"));
  }

  private TaskSubscription subscribe(final TaskType type, final String description,
      final Map<String, String> restrictions) {
    final TaskHandler action = (info, variables) -> events.add(
        new Event(info.getTaskId(), info.getMeta(), Map.copyOf(variables)));
    final TaskTerminationHandler termination = info -> events.add(
        new Event(info.getTaskId(), info.getMeta(), Map.of()));
    return subscriptions.subscribeForTask(new SubscribeForTaskCmd(restrictions, type, description,
        Set.of("runId", "keep", "payload"), action, termination)).join();
  }

  public List<Event> events(final String runId) {
    final var ids = events.stream().filter(e -> runId.equals(e.variables().get("runId")))
        .map(Event::taskId).collect(java.util.stream.Collectors.toSet());
    return events.stream().filter(e -> ids.contains(e.taskId())).toList();
  }

  public void unsubscribeUser() {
    subscriptions.unsubscribe(new UnsubscribeFromTaskCmd(userSubscription)).join();
  }

  public static String strategy() {
    return ConfigProvider.getConfig().getValue(STRATEGY, String.class);
  }

  public record Event(String taskId, Map<String, String> meta, Map<String, Object> variables) {}
}
