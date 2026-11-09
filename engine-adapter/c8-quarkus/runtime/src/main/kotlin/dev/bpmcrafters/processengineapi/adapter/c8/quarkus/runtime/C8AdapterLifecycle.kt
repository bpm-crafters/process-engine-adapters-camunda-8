package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import io.github.oshai.kotlinlogging.KotlinLogging
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Singleton
class C8AdapterLifecycle @Inject constructor(
  private val build: C8AdapterBuildTimeConfig,
  private val config: C8AdapterRuntimeConfig,
  private val services: Instance<SubscribingServiceTaskDelivery>,
  private val pulls: Instance<PullUserTaskDelivery>,
  private val refreshing: Instance<SubscribingRefreshingZeebeJobUserTaskDelivery>,
  private val listeners: Instance<ListenerUserTaskDelivery>,
  private val registrations: Instance<GlobalUserTaskListenerRegistrationHelper>
) {
  companion object {
    /** Runs after application StartupEvent observers and @Startup beans at CDI default priority 2500. */
    const val STARTUP_PRIORITY = 3000
    /** Stops adapter workers before default-priority application/client shutdown observers. */
    const val SHUTDOWN_PRIORITY = 2000
  }

  private val cleanup = mutableListOf<AutoCloseable>()
  private var executor: ScheduledExecutorService? = null
  private var schedule: ScheduledFuture<*>? = null
  private var started = false
  private var stopped = false
  private var stopping = false

  fun start(@Observes @Priority(STARTUP_PRIORITY) event: StartupEvent?) {
    try {
      synchronized(this) {
        if (started || stopped) return
        started = true
        config.validate(build)
        startServices()
        startUsers()
      }
    } catch (failure: RuntimeException) {
      cleanupAfterStartupFailure(failure)
      throw failure
    } catch (failure: Error) {
      cleanupAfterStartupFailure(failure)
      throw failure
    }
  }

  private fun cleanupAfterStartupFailure(failure: Throwable) {
    try {
      stop(null)
    } catch (closingFailure: RuntimeException) {
      if (failure !== closingFailure) failure.addSuppressed(closingFailure)
    } catch (closingFailure: Error) {
      if (failure !== closingFailure) failure.addSuppressed(closingFailure)
    }
  }

  private fun startServices() {
    if (build.serviceTasks().deliveryStrategy().orElseThrow() == C8AdapterBuildTimeConfig.ServiceStrategy.SUBSCRIPTION) {
      val delivery = services.get()
      cleanup.add(delivery)
      delivery.subscribe()
    }
  }

  private fun startUsers() {
    when (build.userTasks().deliveryStrategy().orElseThrow()) {
      C8AdapterBuildTimeConfig.UserStrategy.SCHEDULED -> schedule(pulls.get()::refresh)
      C8AdapterBuildTimeConfig.UserStrategy.SUBSCRIPTION_REFRESHING -> {
        val delivery = refreshing.get()
        cleanup.add(AutoCloseable { delivery.unsubscribeAll() })
        delivery.subscribe()
        schedule(delivery::refresh)
      }
      C8AdapterBuildTimeConfig.UserStrategy.LISTENER -> startListener()
      C8AdapterBuildTimeConfig.UserStrategy.CUSTOM -> Unit // Application owns the custom strategy lifecycle.
    }
  }

  private fun startListener() {
    val delivery = listeners.get()
    cleanup.add(delivery)
    registrations.get().registerIfEnabled()
    if (config.userTasks().listener().preloadExistingTasks()) {
      try {
        pulls.get().refresh()
      } catch (failure: Exception) {
        logger.error(failure) {
          "PROCESS-ENGINE-C8-400: Failed to preload existing user tasks; subscribing to listener jobs."
        }
      }
    }
    delivery.subscribe()
  }

  private fun schedule(action: () -> Unit) {
    val scheduler = newExecutor()
    executor = scheduler
    schedule = scheduler.scheduleAtFixedRate({
      try {
        action()
      } catch (failure: Exception) {
        logger.error(failure) {
          "PROCESS-ENGINE-C8-401: User task delivery iteration failed; subsequent iterations remain scheduled."
        }
      }
    }, 0, config.userTasks().scheduleDeliveryFixedRateInSeconds(), TimeUnit.SECONDS)
  }

  fun newExecutor(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "C8-ADAPTER-USER-TASKS")
  }

  fun stop(@Observes @Priority(SHUTDOWN_PRIORITY) event: ShutdownEvent?) {
    val actions = synchronized(this) {
      check(!stopping) { "Adapter cleanup is already in progress" }
      if (stopped) return
      stopped = true
      stopping = true
      mutableListOf(AutoCloseable { stopSchedule() }).also {
        it.addAll(cleanup)
        cleanup.clear()
      }
    }
    // A draining handler may reenter stop(); never hold the lifecycle lock while waiting for it.
    try {
      closeAll(actions)
    } finally {
      synchronized(this) { stopping = false }
    }
  }

  private fun stopSchedule() {
    schedule?.cancel(false)
    val scheduler = executor ?: return
    scheduler.shutdown()
    try {
      if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
        scheduler.shutdownNow()
        if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
          throw IllegalStateException("PROCESS-ENGINE-C8-402: User task delivery did not stop within 60 seconds.")
        }
      }
    } catch (failure: InterruptedException) {
      scheduler.shutdownNow()
      Thread.currentThread().interrupt()
      throw failure
    }
  }

  private fun closeAll(actions: List<AutoCloseable>) {
    var failure: Throwable? = null
    for (action in actions) {
      try {
        action.close()
      } catch (closingFailure: Throwable) {
        if (failure == null) {
          failure = closingFailure
        } else if (failure !== closingFailure) {
          failure.addSuppressed(closingFailure)
        }
      }
    }
    if (failure is Error) throw failure
    if (failure != null) throw IllegalStateException("PROCESS-ENGINE-C8-403: Adapter cleanup failed.", failure)
  }
}
