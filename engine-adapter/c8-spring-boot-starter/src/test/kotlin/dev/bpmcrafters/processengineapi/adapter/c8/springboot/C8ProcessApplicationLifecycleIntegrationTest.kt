package dev.bpmcrafters.processengineapi.adapter.c8.springboot

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.schedule.C8SchedulingAutoConfiguration
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription.C8SubscriptionAutoConfiguration
import dev.bpmcrafters.processengineapi.lifecycle.ProcessApplicationLifecycle
import dev.bpmcrafters.processengineapi.lifecycle.ProcessApplicationPhase
import dev.bpmcrafters.processengineapi.lifecycle.TaskSubscriptionRegistrationContext
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationLifecycleAutoConfiguration
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import io.camunda.client.CamundaClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.TaskScheduler
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.function.Supplier

class C8ProcessApplicationLifecycleIntegrationTest {

  private val taskScheduler = mock<TaskScheduler>()
  private val scheduledFuture = mock<ScheduledFuture<*>>()

  private val contextRunner = ApplicationContextRunner()
    .withConfiguration(
      AutoConfigurations.of(
        C8AdapterAutoConfiguration::class.java,
        C8CamundaClientAutoConfiguration::class.java,
        C8SubscriptionAutoConfiguration::class.java,
        C8SchedulingAutoConfiguration::class.java,
        ProcessApplicationLifecycleAutoConfiguration::class.java
      )
    )
    .withBean(CamundaClient::class.java, { mock<CamundaClient>() })
    .withBean("c8-task-scheduler", TaskScheduler::class.java, Supplier { taskScheduler })
    .withPropertyValues(
      "${C8AdapterProperties.DEFAULT_PREFIX}.enabled=true",
      "${C8AdapterProperties.DEFAULT_PREFIX}.service-tasks.delivery-strategy=CUSTOM",
      "${C8AdapterProperties.DEFAULT_PREFIX}.service-tasks.worker-id=test-worker",
      "${C8AdapterProperties.DEFAULT_PREFIX}.user-tasks.delivery-strategy=SCHEDULED"
    )

  @Test
  fun `starts scheduled delivery at running and cancels it when stopping`() {
    whenever(taskScheduler.scheduleAtFixedRate(any<Runnable>(), any<Instant>(), any<Duration>())).thenReturn(scheduledFuture)

    contextRunner.run { context ->
      val lifecycle = context.getBean(ProcessApplicationLifecycle::class.java)
      val taskSubscriptionApi = context.getBean(TaskSubscriptionApi::class.java)

      lifecycle.transitionTo(
        ProcessApplicationPhase.RUNNING,
        TaskSubscriptionRegistrationContext(taskSubscriptionApi)
      )

      assertThat(lifecycle.getPhase()).isEqualTo(ProcessApplicationPhase.RUNNING)
      verify(taskScheduler, times(1)).scheduleAtFixedRate(any<Runnable>(), any<Instant>(), any<Duration>())

      lifecycle.transitionTo(ProcessApplicationPhase.STOPPING)

      verify(scheduledFuture, times(1)).cancel(false)
    }
  }
}
