package dev.bpmcrafters.processengineapi.adapter.c8.springboot

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties.Companion.DEFAULT_PREFIX
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties.ServiceTaskDeliveryStrategy.SUBSCRIPTION
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties.UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8ExternalServiceTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.FailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.LinearMemoryFailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import io.camunda.client.CamundaClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration

/**
 * Configuration for task completion.
 */
@Configuration
@AutoConfigureAfter(C8AdapterAutoConfiguration::class)
@Conditional(C8AdapterEnabledCondition::class)
class C8CamundaClientAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  fun defaultFailureRetrySupplier(c8AdapterProperties: C8AdapterProperties): FailureRetrySupplier =
    LinearMemoryFailureRetrySupplier(
      retry = c8AdapterProperties.serviceTasks.retries,
      retryTimeout = c8AdapterProperties.serviceTasks.retryTimeoutInSeconds
    )


  @Bean(name = ["c8-service-task-delivery"])
  @Qualifier("c8-service-task-delivery")
  @ConditionalOnServiceTaskDeliveryStrategy(strategy = SUBSCRIPTION)
  fun subscribingServiceTaskDelivery(
    subscriptionRepository: SubscriptionRepository,
    camundaClient: CamundaClient,
    c8AdapterProperties: C8AdapterProperties
  ) = SubscribingServiceTaskDelivery(
    subscriptionRepository = subscriptionRepository,
    camundaClient = camundaClient,
    workerId = c8AdapterProperties.serviceTasks.workerId,
    retryTimeoutInSeconds = c8AdapterProperties.serviceTasks.retryTimeoutInSeconds
  )

  @Bean(name = ["c8-user-task-delivery"])
  @Qualifier("c8-user-task-delivery")
  @ConditionalOnUserTaskDeliveryStrategy(strategy = SUBSCRIPTION_REFRESHING)
  fun subscribingRefreshingUserTaskDelivery(
    subscriptionRepository: SubscriptionRepository,
    camundaClient: CamundaClient,
    c8AdapterProperties: C8AdapterProperties
  ): SubscribingRefreshingUserTaskDelivery {
    return SubscribingRefreshingUserTaskDelivery(
      subscriptionRepository = subscriptionRepository,
      camundaClient = camundaClient,
      workerId = c8AdapterProperties.serviceTasks.workerId,
      userTaskLockTimeoutMs = c8AdapterProperties.userTasks.scheduleDeliveryFixedRateInSeconds * 1000 * 2
    )
  }

  @Bean("c8-service-task-completion")
  @Qualifier("c8-service-task-completion")
  fun externalTaskCompletionStrategy(
    camundaClient: CamundaClient,
    subscriptionRepository: SubscriptionRepository,
    failureRetrySupplier: FailureRetrySupplier
  ): ServiceTaskCompletionApi =
    C8ExternalServiceTaskCompletionApiImpl(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      failureRetrySupplier = failureRetrySupplier
    )

  @Bean("c8-user-task-completion")
  @Qualifier("c8-user-task-completion")
  @ConditionalOnProperty(prefix = DEFAULT_PREFIX, name = ["user-tasks.completion-strategy"], havingValue = "job")
  fun zeebeUserTaskCompletionStrategy(
    camundaClient: CamundaClient,
    subscriptionRepository: SubscriptionRepository
  ): UserTaskCompletionApi =
    C8CamundaClientUserTaskCompletionApiImpl(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository
    )

}
