package dev.bpmcrafters.example.javac8quarkus.adapter.in.process;

import dev.bpmcrafters.processengineapi.task.*;
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TaskSubscriptions {
  private final TaskSubscriptionApi subscriptions;
  private final ServiceTaskCompletionApi completion;
  private final UserTaskSupport users = new UserTaskSupport();
  private final List<TaskSubscription> services = new ArrayList<>();

  public TaskSubscriptions(final TaskSubscriptionApi subscriptions, final ServiceTaskCompletionApi completion) {
    this.subscriptions = subscriptions;
    this.completion = completion;
  }

  // Default observer priority 2500 precedes adapter startup (3000), and follows shutdown (2000).
  public void start(@Observes final StartupEvent event) {
    register("execute-action-external");
    register("send-message-external");
    users.subscribe(subscriptions, Map.of(), null, null);
  }

  private void register(final String topic) {
    services.add(subscriptions.subscribeForTask(new SubscribeForTaskCmd(Map.of(), TaskType.EXTERNAL,
        topic, null, (task, payload) -> handle(topic, task, payload),
        (TaskInformation task) -> {})).join());
  }

  public UserTaskSupport users() {
    return users;
  }

  public void stop(@Observes final ShutdownEvent event) {
    try {
      for (final var service : services) {
        subscriptions.unsubscribe(new UnsubscribeFromTaskCmd(service)).join();
      }
    } finally {
      services.clear();
      users.unsubscribe(subscriptions);
    }
  }

  void handle(final String topic, final TaskInformation task, final Map<String, ?> payload) {
    try {
      if (topic.equals("execute-action-external") && ((Integer) payload.get("intValue")) % 42 == 0) {
        completion.completeTaskByError(new CompleteTaskByErrorCmd(
            task.getTaskId(), "action_error", "The answer is a multiple of 42", Map::of)).join();
        return;
      }
      final var result = new HashMap<String, Object>(payload);
      result.put(topic.equals("execute-action-external") ? "action1" : "action2",
          topic.equals("execute-action-external") ? "value1" : "value2");
      completion.completeTask(new CompleteTaskCmd(task.getTaskId(), () -> result)).join();
    } catch (final Exception failure) {
      final var retries = task.getMetaValueAsInt(TaskInformation.RETRIES);
      completion.failTask(new FailTaskCmd(task.getTaskId(), failure.toString(), null,
          retries == null ? null : Math.max(0, retries - 1), null)).join();
    }
  }
}
