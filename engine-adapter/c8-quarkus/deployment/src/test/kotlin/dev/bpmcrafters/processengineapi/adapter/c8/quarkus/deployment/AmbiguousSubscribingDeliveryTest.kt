package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.task.TaskSubscription
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import io.quarkus.runtime.Startup
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class AmbiguousSubscribingDeliveryTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = StrategyTestSupport.application("CUSTOM", "CUSTOM")
      .withApplicationRoot { archive -> archive.addClasses(
        StrategyTestSupport::class.java, StrategyTestSupport.Companion::class.java,
        Deliveries::class.java, NoOpDelivery::class.java, EagerConsumer::class.java
      ) }
      .assertException { error -> assertThat(error).hasStackTraceContaining("AmbiguousResolutionException") }
  }

  @Test
  fun `rejects ambiguous custom deliveries`() {
    // Startup must fail rather than omit unsubscribe delegation.
  }

  @Singleton
  @Startup
  class EagerConsumer {
    @field:Inject lateinit var subscriptions: TaskSubscriptionApi
  }

  @Singleton
  class Deliveries {
    @Produces @Singleton @Named("first")
    fun first(): SubscribingUserTaskDelivery = NoOpDelivery()

    @Produces @Singleton @Named("second")
    fun second(): SubscribingUserTaskDelivery = NoOpDelivery()
  }

  class NoOpDelivery : SubscribingUserTaskDelivery {
    override fun unsubscribe(taskSubscription: TaskSubscription) = Unit
  }
}
