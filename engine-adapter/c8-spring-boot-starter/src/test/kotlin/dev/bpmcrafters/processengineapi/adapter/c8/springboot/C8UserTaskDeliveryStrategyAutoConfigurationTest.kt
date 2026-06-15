package dev.bpmcrafters.processengineapi.adapter.c8.springboot

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties.Companion.DEFAULT_PREFIX
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription.C8SubscriptionAutoConfiguration
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription.SubscribingUserTaskDeliveryBinding
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription.UserTaskListenerDeliveryBinding
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription.UserTaskListenerGlobalRegistration
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskJobCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.UserTaskListenerDelivery
import io.camunda.client.CamundaClient
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class C8UserTaskDeliveryStrategyAutoConfigurationTest {

  private val contextRunner = ApplicationContextRunner()
    .withConfiguration(
      AutoConfigurations.of(
        C8AdapterAutoConfiguration::class.java,
        C8CamundaClientAutoConfiguration::class.java,
        C8SubscriptionAutoConfiguration::class.java
      )
    )
    .withBean(CamundaClient::class.java, Supplier { mockk<CamundaClient>(relaxed = true) })
    .withPropertyValues(
      "$DEFAULT_PREFIX.enabled=true",
      "$DEFAULT_PREFIX.service-tasks.delivery-strategy=SUBSCRIPTION",
      "$DEFAULT_PREFIX.service-tasks.worker-id=execute-action-external",
      "$DEFAULT_PREFIX.user-tasks.delivery-strategy=SCHEDULED"
    )

  @Test
  fun `should create listener delivery strategy`() {
    contextRunner
      .withPropertyValues(
        "$DEFAULT_PREFIX.user-tasks.delivery-strategy=LISTENER"
      )
      .run {
        assertThat(it).hasBean("c8-user-task-delivery")
        assertThat(it.getBean("c8-user-task-delivery")).isInstanceOf(UserTaskListenerDelivery::class.java)
        assertThat(it.getBean("c8-user-task-completion")).isInstanceOf(C8CamundaClientUserTaskCompletionApiImpl::class.java)
        assertThat(it.getBean("c8-user-task-delivery-subscription")).isInstanceOf(UserTaskListenerDeliveryBinding::class.java)
        assertThat(it.getBean(UserTaskListenerGlobalRegistration::class.java)).isNotNull()
      }
  }

  @Test
  fun `should keep scheduled delivery strategy unchanged`() {
    contextRunner
      .withPropertyValues(
        "$DEFAULT_PREFIX.user-tasks.delivery-strategy=SCHEDULED"
      )
      .run {
        assertThat(it).hasBean("c8-user-task-delivery")
        assertThat(it.getBean("c8-user-task-delivery")).isInstanceOf(PullUserTaskDelivery::class.java)
        assertThat(it.getBean("c8-user-task-completion")).isInstanceOf(C8CamundaClientUserTaskCompletionApiImpl::class.java)
        assertThat(it).doesNotHaveBean("c8-user-task-delivery-subscription")
      }
  }

  @Test
  fun `should keep subscription refreshing delivery strategy unchanged`() {
    contextRunner
      .withPropertyValues(
        "$DEFAULT_PREFIX.user-tasks.delivery-strategy=SUBSCRIPTION_REFRESHING"
      )
      .run {
        assertThat(it).hasBean("c8-user-task-delivery")
        assertThat(it.getBean("c8-user-task-delivery")).isInstanceOf(SubscribingRefreshingUserTaskDelivery::class.java)
        assertThat(it.getBean("c8-user-task-completion")).isInstanceOf(C8CamundaClientUserTaskJobCompletionApiImpl::class.java)
        assertThat(it.getBean("c8-user-task-delivery-subscription")).isInstanceOf(SubscribingUserTaskDeliveryBinding::class.java)
      }
  }
}
