package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.*
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties.ServiceTaskDeliveryStrategy.SUBSCRIPTION
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties.UserTaskDeliveryStrategy.LISTENER
import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties.UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import io.camunda.client.CamundaClient
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

private val logger = KotlinLogging.logger {}

@AutoConfigureAfter(C8AdapterAutoConfiguration::class)
@Conditional(C8AdapterEnabledCondition::class)
class C8SubscriptionAutoConfiguration {

  @PostConstruct
  fun report() {
    logger.debug { "PROCESS-ENGINE-C8-203: Subscription configuration applied." }
  }

  @Bean("c8-service-task-delivery-subscription")
  @ConditionalOnServiceTaskDeliveryStrategy(strategy = SUBSCRIPTION)
  fun subscribingServiceTaskDeliveryBinding(
    @Qualifier("c8-service-task-delivery")
    subscribingServiceTaskDelivery: SubscribingServiceTaskDelivery
  ): SubscribingServiceTaskDeliveryBinding {
    return SubscribingServiceTaskDeliveryBinding(
      subscribingServiceTaskDelivery = subscribingServiceTaskDelivery
    )
  }

  @Bean("c8-user-task-delivery-subscription")
  @ConditionalOnUserTaskDeliveryStrategy(strategy = SUBSCRIPTION_REFRESHING)
  fun subscribingUserTaskDeliveryBinding(
    @Qualifier("c8-user-task-delivery")
    subscribingRefreshingZeebeJobUserTaskDelivery: SubscribingRefreshingZeebeJobUserTaskDelivery,
  ): SubscribingUserTaskDeliveryBinding {
    return SubscribingUserTaskDeliveryBinding(
      subscribingRefreshingZeebeJobUserTaskDelivery = subscribingRefreshingZeebeJobUserTaskDelivery
    )
  }

  @Bean
  @ConditionalOnUserTaskDeliveryStrategy(strategy = LISTENER)
  fun userTaskListenerGlobalRegistration(
    camundaClient: CamundaClient,
    c8AdapterProperties: C8AdapterProperties,
  ): GlobalUserTaskListenerRegistrationHelper =
    GlobalUserTaskListenerRegistrationHelper(
      camundaClient = camundaClient,
      autoRegisterGlobalListener = c8AdapterProperties.userTasks.listener.autoRegisterGlobalListener,
      globalListenerId = c8AdapterProperties.userTasks.listener.globalListenerId,
      topic = c8AdapterProperties.userTasks.listener.topic,
      globalListenerRetries = c8AdapterProperties.userTasks.listener.globalListenerRetries,
      globalListenerAfterNonGlobal = c8AdapterProperties.userTasks.listener.globalListenerAfterNonGlobal,
      globalListenerPriority = c8AdapterProperties.userTasks.listener.globalListenerPriority
    )

  @Bean("c8-user-task-delivery-subscription")
  @ConditionalOnUserTaskDeliveryStrategy(strategy = LISTENER)
  fun userTaskListenerDeliveryBinding(
    @Qualifier("c8-user-task-delivery")
    listenerUserTaskDelivery: ListenerUserTaskDelivery,
    globalUserTaskListenerRegistrationHelper: GlobalUserTaskListenerRegistrationHelper,
  ): UserTaskListenerDeliveryBinding {
    return UserTaskListenerDeliveryBinding(
      listenerUserTaskDelivery = listenerUserTaskDelivery,
      globalUserTaskListenerRegistrationHelper = globalUserTaskListenerRegistrationHelper
    )
  }
}
