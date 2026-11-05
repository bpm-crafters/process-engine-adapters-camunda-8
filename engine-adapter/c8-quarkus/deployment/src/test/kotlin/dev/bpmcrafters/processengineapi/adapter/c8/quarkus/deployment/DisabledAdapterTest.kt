package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8AdapterLifecycle
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.FailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi
import dev.bpmcrafters.processengineapi.correlation.SignalApi
import dev.bpmcrafters.processengineapi.decision.EvaluateDecisionApi
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.process.StartProcessApi
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.UserTaskModificationApi
import io.quarkus.arc.Arc
import io.quarkus.test.QuarkusUnitTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class DisabledAdapterTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = QuarkusUnitTest()
      .withEmptyApplication()
      .overrideConfigKey("quarkus.camunda.devservices.enabled", "false")
      .overrideConfigKey("quarkus.camunda.resources.enabled", "false")
      .overrideRuntimeConfigKey("quarkus.camunda.active", "false")
  }

  @Test
  fun `adapter is disabled by default`() {
    for (type in listOf(
      DeploymentApi::class.java, StartProcessApi::class.java, CorrelationApi::class.java, SignalApi::class.java,
      EvaluateDecisionApi::class.java, TaskSubscriptionApi::class.java, ServiceTaskCompletionApi::class.java,
      UserTaskCompletionApi::class.java, UserTaskModificationApi::class.java, SubscriptionRepository::class.java,
      FailureRetrySupplier::class.java, C8AdapterLifecycle::class.java, SubscribingServiceTaskDelivery::class.java,
      ListenerUserTaskDelivery::class.java, PullUserTaskDelivery::class.java, SubscribingRefreshingZeebeJobUserTaskDelivery::class.java
    )) {
      assertThat(Arc.container().instance(type).isAvailable).`as`(type.simpleName).isFalse()
    }
  }
}
