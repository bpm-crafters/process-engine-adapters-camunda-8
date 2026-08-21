package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.ServiceTaskDeliveryStrategy
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.UserTaskDeliveryStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Singleton
import jakarta.interceptor.Interceptor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Subscribes the configured deliveries on startup and refreshes refreshable deliveries at a fixed
 * rate, mirroring `C8SubscriptionAutoConfiguration` and `C8SchedulingAutoConfiguration` of the
 * Spring Boot starter. Uses a dedicated scheduler analogous to the Spring starter's
 * `ThreadPoolTaskScheduler` instead of forcing the quarkus-scheduler extension on applications.
 */
@Singleton
class C8AdapterLifecycle(
  private val properties: C8AdapterProperties,
  private val bindings: Instance<C8AdapterBindings>
) {

  private var scheduler: ScheduledExecutorService? = null

  /**
   * Runs after application startup observers with default priority, so applications can register
   * their task subscriptions in an own [StartupEvent] observer before the deliveries subscribe —
   * the equivalent of registering handlers during bean initialization with the Spring Boot starter.
   */
  fun onStart(@Suppress("UNUSED_PARAMETER") @Observes @Priority(Interceptor.Priority.APPLICATION + 900) event: StartupEvent) {
    if (!properties.enabled()) {
      logger.debug { "PROCESS-ENGINE-C8-120: C8 adapter is disabled, skipping lifecycle bindings." }
      return
    }
    // Validate the required configuration synchronously, so a missing key aborts the startup —
    // the equivalent of Spring's configuration properties binding error.
    val serviceTaskStrategy = properties.requiredServiceTaskDeliveryStrategy()
    val userTaskStrategy = properties.requiredUserTaskDeliveryStrategy()
    if (serviceTaskStrategy == ServiceTaskDeliveryStrategy.SUBSCRIPTION || userTaskStrategy == UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING) {
      properties.requiredServiceTaskWorkerId()
    }
    logger.debug {
      "PROCESS-ENGINE-C8-204: Quarkus adapter wiring applied (serviceTasks=$serviceTaskStrategy, userTasks=$userTaskStrategy)."
    }
    val adapterBindings = bindings.get()
    val executor = Executors.newScheduledThreadPool(2, SchedulerThreadFactory())
    scheduler = executor
    // subscribe service and user task deliveries independently, mirroring the separate
    // @Async event listeners of the Spring Boot starter
    executor.execute {
      try {
        adapterBindings.startServiceTasks()
      } catch (e: Throwable) {
        logger.error(e) { "PROCESS-ENGINE-C8-121: Failed to subscribe service task delivery on startup." }
      }
    }
    executor.execute {
      try {
        adapterBindings.startUserTasks()
      } catch (e: Throwable) {
        logger.error(e) { "PROCESS-ENGINE-C8-128: Failed to subscribe user task delivery on startup." }
      }
    }
    adapterBindings.refreshableUserTaskDelivery?.let { delivery ->
      val fixedRateInSeconds = properties.userTasks().scheduleDeliveryFixedRateInSeconds()
      val refreshing = userTaskStrategy == UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING
      executor.scheduleAtFixedRate(
        {
          try {
            if (refreshing) {
              logger.trace { "PROCESS-ENGINE-C8-124: Refreshing user tasks..." }
              delivery.refresh()
              logger.trace { "PROCESS-ENGINE-C8-125: Refreshed user tasks." }
            } else {
              logger.trace { "PROCESS-ENGINE-C8-126: Delivering user tasks..." }
              delivery.refresh()
              logger.trace { "PROCESS-ENGINE-C8-127: Delivered user tasks." }
            }
          } catch (e: Throwable) {
            // catch everything, an escaping throwable would cancel the periodic task silently
            if (executor.isShutdown) {
              logger.debug(e) { "PROCESS-ENGINE-C8-122: User task refresh interrupted during shutdown." }
            } else {
              logger.error(e) { "PROCESS-ENGINE-C8-122: Failed to refresh user tasks." }
            }
          }
        },
        0,
        fixedRateInSeconds,
        TimeUnit.SECONDS
      )
    }
  }

  fun onStop(@Suppress("UNUSED_PARAMETER") @Observes event: ShutdownEvent) {
    val executor = scheduler ?: return
    scheduler = null
    executor.shutdownNow()
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    try {
      bindings.get().close()
    } catch (e: Exception) {
      logger.warn(e) { "PROCESS-ENGINE-C8-122: Failed to close task deliveries on shutdown." }
    }
    logger.debug { "PROCESS-ENGINE-C8-123: C8 adapter lifecycle stopped." }
  }

  private class SchedulerThreadFactory : ThreadFactory {
    private val counter = AtomicInteger(1)
    override fun newThread(runnable: Runnable): Thread =
      Thread(runnable, "C8REMOTE-SCHEDULER-${counter.getAndIncrement()}").apply { isDaemon = true }
  }
}
