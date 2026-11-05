package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.correlation.CorrelationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.correlation.SignalApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.decision.EvaluateDecisionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.deploy.DeploymentApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.process.StartProcessApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8AdapterBuildTimeConfig
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskJobCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8ExternalServiceTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.FailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.LinearMemoryFailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.modification.C8CamundaClientUserTaskModificationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.subscription.C8TaskSubscriptionApiImpl
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi
import dev.bpmcrafters.processengineapi.correlation.SignalApi
import dev.bpmcrafters.processengineapi.decision.EvaluateDecisionApi
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.process.StartProcessApi
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.UserTaskModificationApi
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

abstract class StrategyTestSupport {
  @field:Inject lateinit var config: C8AdapterBuildTimeConfig
  @field:Inject lateinit var deployment: DeploymentApi
  @field:Inject lateinit var start: StartProcessApi
  @field:Inject lateinit var correlation: CorrelationApi
  @field:Inject lateinit var signal: SignalApi
  @field:Inject lateinit var decisions: EvaluateDecisionApi
  @field:Inject lateinit var subscriptions: TaskSubscriptionApi
  @field:Inject lateinit var serviceCompletion: ServiceTaskCompletionApi
  @field:Inject lateinit var modification: UserTaskModificationApi
  @field:Inject lateinit var repository: SubscriptionRepository
  @field:Inject lateinit var retries: FailureRetrySupplier
  @field:Inject lateinit var userCompletion: Instance<UserTaskCompletionApi>
  @field:Inject lateinit var services: Instance<SubscribingServiceTaskDelivery>
  @field:Inject lateinit var pulls: Instance<PullUserTaskDelivery>
  @field:Inject lateinit var refreshing: Instance<SubscribingRefreshingZeebeJobUserTaskDelivery>
  @field:Inject lateinit var listeners: Instance<ListenerUserTaskDelivery>
  @field:Inject lateinit var registrations: Instance<GlobalUserTaskListenerRegistrationHelper>

  companion object {
    fun application(service: String, user: String): QuarkusUnitTest = QuarkusUnitTest().withEmptyApplication()
      .overrideConfigKey("quarkus.process-engine-api.c8.enabled", "true")
      .overrideConfigKey("quarkus.process-engine-api.c8.service-tasks.delivery-strategy", service)
      .overrideConfigKey("quarkus.process-engine-api.c8.user-tasks.delivery-strategy", user)
      .overrideConfigKey("quarkus.camunda.devservices.enabled", "false")
      .overrideConfigKey("quarkus.camunda.resources.enabled", "false")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.service-tasks.worker-id", "test-worker")
      .overrideRuntimeConfigKey("quarkus.camunda.active", "false")
  }

  @Test
  fun `supplies core APIs and defaults`() {
    assertThat(deployment).isExactlyInstanceOf(DeploymentApiImpl::class.java)
    assertThat(start).isExactlyInstanceOf(StartProcessApiImpl::class.java)
    assertThat(correlation).isExactlyInstanceOf(CorrelationApiImpl::class.java)
    assertThat(signal).isExactlyInstanceOf(SignalApiImpl::class.java)
    assertThat(decisions).isExactlyInstanceOf(EvaluateDecisionApiImpl::class.java)
    assertThat(subscriptions).isExactlyInstanceOf(C8TaskSubscriptionApiImpl::class.java)
    assertThat(serviceCompletion).isExactlyInstanceOf(C8ExternalServiceTaskCompletionApiImpl::class.java)
    assertThat(modification).isExactlyInstanceOf(C8CamundaClientUserTaskModificationApiImpl::class.java)
    assertThat(repository).isExactlyInstanceOf(InMemSubscriptionRepository::class.java)
    assertThat(retries).isExactlyInstanceOf(LinearMemoryFailureRetrySupplier::class.java)
  }

  @Test
  fun `selects only configured strategies`() {
    assertThat(services.isResolvable).isEqualTo(
      config.serviceTasks().deliveryStrategy().orElseThrow() == C8AdapterBuildTimeConfig.ServiceStrategy.SUBSCRIPTION
    )
    val user = config.userTasks().deliveryStrategy().orElseThrow()
    assertThat(pulls.isResolvable).isEqualTo(
      user == C8AdapterBuildTimeConfig.UserStrategy.SCHEDULED || user == C8AdapterBuildTimeConfig.UserStrategy.LISTENER
    )
    assertThat(refreshing.isResolvable).isEqualTo(user == C8AdapterBuildTimeConfig.UserStrategy.SUBSCRIPTION_REFRESHING)
    assertThat(listeners.isResolvable).isEqualTo(user == C8AdapterBuildTimeConfig.UserStrategy.LISTENER)
    assertThat(registrations.isResolvable).isEqualTo(user == C8AdapterBuildTimeConfig.UserStrategy.LISTENER)
    when (user) {
      C8AdapterBuildTimeConfig.UserStrategy.CUSTOM -> assertThat(userCompletion.isUnsatisfied).isTrue()
      C8AdapterBuildTimeConfig.UserStrategy.SUBSCRIPTION_REFRESHING -> assertThat(userCompletion.get())
        .isExactlyInstanceOf(C8CamundaClientUserTaskJobCompletionApiImpl::class.java)
      C8AdapterBuildTimeConfig.UserStrategy.SCHEDULED, C8AdapterBuildTimeConfig.UserStrategy.LISTENER ->
        assertThat(userCompletion.get()).isExactlyInstanceOf(C8CamundaClientUserTaskCompletionApiImpl::class.java)
    }
  }
}
