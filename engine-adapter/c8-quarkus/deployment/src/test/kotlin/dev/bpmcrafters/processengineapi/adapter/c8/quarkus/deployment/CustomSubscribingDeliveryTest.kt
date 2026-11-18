package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskSubscription
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler
import dev.bpmcrafters.processengineapi.task.TaskType
import dev.bpmcrafters.processengineapi.task.UnsubscribeFromTaskCmd
import io.quarkus.test.QuarkusUnitTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CustomSubscribingDeliveryTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = StrategyTestSupport.application("CUSTOM", "CUSTOM")
      .withApplicationRoot { archive -> archive.addClasses(
        StrategyTestSupport::class.java, StrategyTestSupport.Companion::class.java, CustomDelivery::class.java
      ) }
  }

  @field:Inject lateinit var subscriptions: TaskSubscriptionApi
  @field:Inject lateinit var delivery: CustomDelivery

  @Test
  fun `delegates unsubscription to application delivery`() {
    val subscription = subscriptions.subscribeForTask(SubscribeForTaskCmd(
      emptyMap(), TaskType.USER, "approve", setOf("orderId"), TaskHandler { _, _ -> }, TaskTerminationHandler { }
    )).join()
    subscriptions.unsubscribe(UnsubscribeFromTaskCmd(subscription)).join()
    assertThat(delivery.unsubscribed).isSameAs(subscription)
  }

  @Singleton
  class CustomDelivery : SubscribingUserTaskDelivery {
    var unsubscribed: TaskSubscription? = null

    override fun unsubscribe(taskSubscription: TaskSubscription) {
      unsubscribed = taskSubscription
    }
  }
}
