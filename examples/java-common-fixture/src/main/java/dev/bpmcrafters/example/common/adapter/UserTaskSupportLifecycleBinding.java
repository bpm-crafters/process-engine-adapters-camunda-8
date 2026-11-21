package dev.bpmcrafters.example.common.adapter;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationStoppingEvent;
import dev.bpmcrafters.processengineapi.springboot.lifecycle.TaskSubscriptionRegistrationEvent;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport;
import org.springframework.context.event.EventListener;

public class UserTaskSupportLifecycleBinding {

  private final UserTaskSupport userTaskSupport;
  private final TaskSubscriptionApi taskSubscriptionApi;

  public UserTaskSupportLifecycleBinding(UserTaskSupport userTaskSupport, TaskSubscriptionApi taskSubscriptionApi) {
    this.userTaskSupport = userTaskSupport;
    this.taskSubscriptionApi = taskSubscriptionApi;
  }

  @EventListener
  public void register(TaskSubscriptionRegistrationEvent event) {
    userTaskSupport.subscribe(event.getTaskSubscriptionApi(), CommonRestrictions.builder().build(), null, null);
  }

  @EventListener
  public void unregister(ProcessApplicationStoppingEvent event) {
    userTaskSupport.unsubscribe(taskSubscriptionApi);
  }
}
