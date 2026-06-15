package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import io.camunda.client.api.search.enums.ListenerEventType

data class UserTaskListenerEvent(
  val taskId: String,
  val eventType: ListenerEventType
)
