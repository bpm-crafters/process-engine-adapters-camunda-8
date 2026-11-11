package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8AdapterLifecycle
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3
import io.quarkus.arc.Arc
import io.quarkus.runtime.Startup
import io.quarkus.runtime.StartupEvent
import io.quarkus.test.QuarkusUnitTest
import jakarta.annotation.PostConstruct
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class StartupOrderingTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = StrategyTestSupport.application("SUBSCRIPTION", "CUSTOM")
      .withApplicationRoot { archive -> archive.addClasses(
        StrategyTestSupport::class.java, StrategyTestSupport.Companion::class.java, State::class.java,
        ClientProducer::class.java, ApplicationSubscriptions::class.java, StartupBean::class.java
      ) }
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.grpc-address", "unused invalid endpoint")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.oauth.client-id", "unused-partial-oauth")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.request-timeout", "not-a-duration")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.client.prefer-rest-over-grpc", "not-a-boolean")
  }

  @field:Inject lateinit var state: State
  @field:Inject lateinit var lifecycle: C8AdapterLifecycle
  @field:Inject lateinit var client: CamundaClient

  @Test
  fun `application startup registration precedes worker creation and adapter does not close client`() {
    assertThat(state.events).contains("startup-bean", "subscription-registered", "worker-opened")
    assertThat(state.events.indexOf("worker-opened")).isGreaterThan(state.events.indexOf("startup-bean"))
    assertThat(state.events.indexOf("worker-opened")).isGreaterThan(state.events.indexOf("subscription-registered"))
    lifecycle.stop(null)
    verify(state.worker).close()
    Arc.container().instance(CamundaClient::class.java).destroy()
    verify(client, never()).close()
  }

  @Singleton
  class State {
    val events = mutableListOf<String>()
    val worker = mock<JobWorker>()
  }

  @Singleton
  @Startup
  class StartupBean {
    @field:Inject lateinit var state: State

    @PostConstruct
    fun started() {
      state.events.add("startup-bean")
    }
  }

  @Singleton
  class ApplicationSubscriptions {
    @field:Inject lateinit var subscriptions: TaskSubscriptionApi
    @field:Inject lateinit var state: State

    fun register(@Observes event: StartupEvent) {
      subscriptions.subscribeForTask(SubscribeForTaskCmd(
        emptyMap(), TaskType.EXTERNAL, "test-job", emptySet(), TaskHandler { _, _ -> }, TaskTerminationHandler { }
      )).join()
      state.events.add("subscription-registered")
    }
  }

  @Singleton
  class ClientProducer {
    @Produces
    @Singleton
    fun client(state: State): CamundaClient {
      val client = mock<CamundaClient>()
      val first = mock<JobWorkerBuilderStep1>()
      val second = mock<JobWorkerBuilderStep2>()
      val third = mock<JobWorkerBuilderStep3>(defaultAnswer = RETURNS_SELF)
      whenever(client.newWorker()).thenReturn(first)
      whenever(first.jobType(any())).thenReturn(second)
      whenever(second.handler(any())).thenReturn(third)
      whenever(third.open()).thenAnswer {
        state.events.add("worker-opened")
        state.worker
      }
      return client
    }
  }
}
