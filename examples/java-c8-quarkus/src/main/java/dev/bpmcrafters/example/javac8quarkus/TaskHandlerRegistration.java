package dev.bpmcrafters.example.javac8quarkus;

import dev.bpmcrafters.example.common.adapter.in.process.ExecuteActionTaskHandler;
import dev.bpmcrafters.example.common.adapter.in.process.SendingTaskHandler;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * Registers the task handlers of the common fixture on startup. The adapter subscribes its
 * deliveries in a startup observer running after those with default priority, so all handlers
 * registered here are picked up — the counterpart of Spring's
 * {@code @Bean(initMethod = "register", destroyMethod = "unregister")}.
 */
@Singleton
public class TaskHandlerRegistration {

  private final ExecuteActionTaskHandler executeActionTaskHandler;
  private final SendingTaskHandler sendingTaskHandler;
  private final UserTaskSupport userTaskSupport;

  TaskHandlerRegistration(
      TaskSubscriptionApi taskSubscriptionApi,
      ServiceTaskCompletionApi serviceTaskCompletionApi,
      UserTaskSupport userTaskSupport
  ) {
    this.executeActionTaskHandler = new ExecuteActionTaskHandler(taskSubscriptionApi, serviceTaskCompletionApi);
    this.sendingTaskHandler = new SendingTaskHandler(taskSubscriptionApi, serviceTaskCompletionApi);
    this.userTaskSupport = userTaskSupport;
  }

  void onStart(@Observes StartupEvent event) {
    executeActionTaskHandler.register();
    sendingTaskHandler.register();
    // touch the lazy user task support bean, so it subscribes for user tasks
    userTaskSupport.getAllTasks();
  }

  void onStop(@Observes ShutdownEvent event) {
    executeActionTaskHandler.unregister();
    sendingTaskHandler.unregister();
  }
}
