package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler
import dev.bpmcrafters.processengineapi.task.TaskType
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test process application registering its task subscriptions in a startup observer with default
 * priority — the adapter lifecycle observer runs afterwards and subscribes the deliveries.
 */
@Singleton
class ITestProcessApplication(
  private val taskSubscriptionApi: TaskSubscriptionApi,
  private val serviceTaskCompletionApi: ServiceTaskCompletionApi
) {

  val completedServiceTaskIds = CopyOnWriteArrayList<String>()
  val userTaskSupport = UserTaskSupport()

  fun onStart(@Suppress("UNUSED_PARAMETER") @Observes event: StartupEvent) {
    taskSubscriptionApi.subscribeForTask(
      SubscribeForTaskCmd(
        CommonRestrictions.builder().build(),
        TaskType.EXTERNAL,
        "execute-action-external",
        null,
        object : TaskHandler {
          override fun accept(taskInformation: TaskInformation, payload: Map<String, Any?>) {
            serviceTaskCompletionApi.completeTask(
              CompleteTaskCmd(taskInformation.taskId, mapOf("serviceResult" to "done"))
            ).get()
            completedServiceTaskIds.add(taskInformation.taskId)
          }
        },
        object : TaskTerminationHandler {
          override fun accept(taskInformation: TaskInformation) = Unit
        }
      )
    ).get()
    userTaskSupport.subscribe(taskSubscriptionApi, CommonRestrictions.builder().build(), null, null)
  }
}
