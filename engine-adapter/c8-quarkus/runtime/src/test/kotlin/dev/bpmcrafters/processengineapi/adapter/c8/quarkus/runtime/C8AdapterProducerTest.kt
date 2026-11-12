package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import dev.bpmcrafters.processengineapi.adapter.c8.correlation.CorrelationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.correlation.SignalApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.decision.EvaluateDecisionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.deploy.DeploymentApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.process.StartProcessApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskJobCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8ExternalServiceTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.modification.C8CamundaClientUserTaskModificationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.subscription.C8TaskSubscriptionApiImpl
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.task.TaskSubscription
import io.camunda.client.CamundaClient
import jakarta.enterprise.inject.AmbiguousResolutionException
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class C8AdapterProducerTest {
  private val client = mock<CamundaClient>()
  private val repository = InMemSubscriptionRepository()

  @Test
  fun `creates core APIs and configured retry supplier`() {
    val producer = C8AdapterProducer()
    val config = ConfigTestData.enabled("CUSTOM", "CUSTOM", mapOf(
      "service-tasks.retries" to "8", "service-tasks.retry-timeout-in-seconds" to "13"
    )).runtime
    val retries = producer.failureRetrySupplier(config)
    assertThat(retries.apply("task-1")).extracting("retryCount", "retryTimeout").containsExactly(7, 13L)
    assertThat(producer.subscriptionRepository()).isExactlyInstanceOf(InMemSubscriptionRepository::class.java)
    assertThat(producer.deploymentApi(client)).isExactlyInstanceOf(DeploymentApiImpl::class.java)
    assertThat(producer.startProcessApi(client)).isExactlyInstanceOf(StartProcessApiImpl::class.java)
    assertThat(producer.correlationApi(client)).isExactlyInstanceOf(CorrelationApiImpl::class.java)
    assertThat(producer.signalApi(client)).isExactlyInstanceOf(SignalApiImpl::class.java)
    assertThat(producer.evaluateDecisionApi(client)).isExactlyInstanceOf(EvaluateDecisionApiImpl::class.java)
    assertThat(producer.serviceTaskCompletionApi(client, repository, retries))
      .isExactlyInstanceOf(C8ExternalServiceTaskCompletionApiImpl::class.java)
    assertThat(producer.userTaskModificationApi(client)).isExactlyInstanceOf(C8CamundaClientUserTaskModificationApiImpl::class.java)
  }

  @Test
  fun `optional delivery is absent or resolved but never silently ignores ambiguity`() {
    val producer = C8AdapterProducer()
    val delivery = mock<Instance<SubscribingUserTaskDelivery>>()
    whenever(delivery.isUnsatisfied).thenReturn(true)
    assertThat(producer.taskSubscriptionApi(repository, delivery)).isExactlyInstanceOf(C8TaskSubscriptionApiImpl::class.java)
    whenever(delivery.isUnsatisfied).thenReturn(false)
    val custom = object : SubscribingUserTaskDelivery {
      override fun unsubscribe(taskSubscription: TaskSubscription) = Unit
    }
    whenever(delivery.get()).thenReturn(custom)
    assertThat(producer.taskSubscriptionApi(repository, delivery)).extracting("subscribingUserTaskDelivery").isSameAs(custom)
    val ambiguous = AmbiguousResolutionException("multiple subscribing deliveries")
    whenever(delivery.get()).thenThrow(ambiguous)
    assertThatThrownBy { producer.taskSubscriptionApi(repository, delivery) }.isSameAs(ambiguous)
  }

  @Test
  fun `service delivery uses service operational settings`() {
    val config = ConfigTestData.enabled("SUBSCRIPTION", "CUSTOM", mapOf(
      "service-tasks.worker-id" to "service-name", "service-tasks.retry-timeout-in-seconds" to "11",
      "service-tasks.lock-time-in-seconds" to "42"
    )).runtime
    val delivery = C8ServiceTaskProducer().delivery(client, repository, config)
    assertThat(delivery).extracting("camundaClient", "subscriptionRepository", "workerId", "retryTimeoutInSeconds", "lockDurationInSeconds")
      .containsExactly(client, repository, "service-name", 11L, 42L)
  }

  @Test
  fun `refreshing uses service worker name and twice schedule in milliseconds`() {
    val producer = C8RefreshingTaskProducer()
    val config = ConfigTestData.enabled("CUSTOM", "SUBSCRIPTION_REFRESHING", mapOf(
      "service-tasks.worker-id" to "service-worker", "user-tasks.schedule-delivery-fixed-rate-in-seconds" to "7"
    )).runtime
    assertThat(producer.delivery(client, repository, config)).extracting("workerId", "userTaskLockTimeoutMs")
      .containsExactly("service-worker", 14000L)
    assertThat(producer.completion(client, repository)).isExactlyInstanceOf(C8CamundaClientUserTaskJobCompletionApiImpl::class.java)
  }

  @Test
  fun `native user delivery and completion use shared client and repository`() {
    val producer = C8NativeUserTaskProducer()
    assertThat(producer.delivery(client, repository)).extracting("camundaClient", "subscriptionRepository")
      .containsExactly(client, repository)
    assertThat(producer.completion(client, repository)).isExactlyInstanceOf(C8CamundaClientUserTaskCompletionApiImpl::class.java)
  }

  @Test
  fun `listener delivery and registration use all operational settings`() {
    val config = ConfigTestData.enabled("CUSTOM", "LISTENER", mapOf(
      "user-tasks.listener.topic" to "topic", "user-tasks.listener.worker-id" to "listener-worker",
      "user-tasks.listener.max-jobs-active" to "17", "user-tasks.listener.stream-enabled" to "false",
      "user-tasks.listener.lock-time-in-seconds" to "41", "user-tasks.listener.retry-timeout-in-seconds" to "19"
    )).runtime
    val producer = C8ListenerTaskProducer()
    assertThat(producer.delivery(client, repository, config))
      .extracting("topic", "workerId", "maxJobsActive", "streamEnabled", "lockTimeInSeconds", "retryTimeoutInSeconds")
      .containsExactly("topic", "listener-worker", 17, false, 41L, 19L)
    assertThat(producer.registration(client, config)).extracting(
      "autoRegisterGlobalListener", "globalListenerId", "topic", "globalListenerRetries",
      "globalListenerAfterNonGlobal", "globalListenerPriority"
    ).containsExactly(false, "process-engine-user-tasks", "topic", 3, true, 0)
  }
}
