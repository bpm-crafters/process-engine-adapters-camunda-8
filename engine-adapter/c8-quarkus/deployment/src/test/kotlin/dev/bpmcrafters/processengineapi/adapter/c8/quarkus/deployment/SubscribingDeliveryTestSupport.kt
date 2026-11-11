package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8AdapterBuildTimeConfig
import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskSubscription
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler
import dev.bpmcrafters.processengineapi.task.TaskType
import dev.bpmcrafters.processengineapi.task.UnsubscribeFromTaskCmd
import io.camunda.client.CamundaClient
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3
import io.quarkus.arc.Arc
import io.quarkus.runtime.StartupEvent
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

abstract class SubscribingDeliveryTestSupport {
  @field:Inject lateinit var config: C8AdapterBuildTimeConfig
  @field:Inject lateinit var subscriptions: TaskSubscriptionApi
  @field:Inject lateinit var repository: SubscriptionRepository
  @field:Inject lateinit var state: StartupSubscription

  companion object {
    fun application(strategy: String): QuarkusUnitTest = QuarkusUnitTest()
      .withApplicationRoot { archive -> archive.addClasses(
        SubscribingDeliveryTestSupport::class.java, Companion::class.java,
        StartupSubscription::class.java, ClientProducer::class.java
      ) }
      .overrideConfigKey("quarkus.process-engine-api.c8.enabled", "true")
      .overrideConfigKey("quarkus.process-engine-api.c8.service-tasks.delivery-strategy", "CUSTOM")
      .overrideConfigKey("quarkus.process-engine-api.c8.user-tasks.delivery-strategy", strategy)
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.service-tasks.worker-id", "test-worker")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.user-tasks.listener.preload-existing-tasks", "false")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.user-tasks.schedule-delivery-fixed-rate-in-seconds", "3600")
  }

  @Test
  fun `interface and subscription API resolve the same concrete delivery as lifecycle`() {
    val subscribing = Arc.container().instance(SubscribingUserTaskDelivery::class.java)
    assertThat(subscribing.isAvailable).`as`("built-in subscribing delivery interface").isTrue()
    val concrete = if (isRefreshing()) {
      Arc.container().instance(SubscribingRefreshingZeebeJobUserTaskDelivery::class.java).get()
    } else {
      Arc.container().instance(ListenerUserTaskDelivery::class.java).get()
    }
    assertThat(subscribing.get()).isSameAs(concrete)
    assertThat(subscriptions).extracting("subscribingUserTaskDelivery").isSameAs(concrete)
  }

  @Test
  fun `API unsubscribe reaches the selected built in delivery`() {
    if (!isRefreshing()) {
      repository.activateSubscriptionForTask("user-task-1", state.subscription as TaskSubscriptionHandle)
    }
    subscriptions.unsubscribe(UnsubscribeFromTaskCmd(state.subscription)).join()
    assertThat(repository.getTaskSubscriptions()).isEmpty()
    if (isRefreshing()) {
      verify(state.worker).close()
    } else {
      assertThat(repository.getActiveSubscriptionForTask("user-task-1")).isNull()
      verify(state.worker, never()).close()
    }
  }

  private fun isRefreshing(): Boolean =
    config.userTasks().deliveryStrategy().orElseThrow() == C8AdapterBuildTimeConfig.UserStrategy.SUBSCRIPTION_REFRESHING

  @Singleton
  class StartupSubscription {
    val worker = mock<JobWorker>()
    lateinit var subscription: TaskSubscription

    fun register(@Observes event: StartupEvent, subscriptions: TaskSubscriptionApi) {
      subscription = subscriptions.subscribeForTask(SubscribeForTaskCmd(
        emptyMap(), TaskType.USER, "approve", emptySet(), TaskHandler { _, _ -> }, TaskTerminationHandler { }
      )).join()
    }
  }

  @Singleton
  class ClientProducer {
    @Produces
    @Singleton
    fun client(state: StartupSubscription): CamundaClient {
      val client = mock<CamundaClient>()
      val first = mock<JobWorkerBuilderStep1>()
      val second = mock<JobWorkerBuilderStep2>()
      val third = mock<JobWorkerBuilderStep3>(defaultAnswer = RETURNS_SELF)
      whenever(client.newWorker()).thenReturn(first)
      whenever(first.jobType(any())).thenReturn(second)
      whenever(second.handler(any())).thenReturn(third)
      whenever(third.open()).thenReturn(state.worker)
      return client
    }
  }
}
