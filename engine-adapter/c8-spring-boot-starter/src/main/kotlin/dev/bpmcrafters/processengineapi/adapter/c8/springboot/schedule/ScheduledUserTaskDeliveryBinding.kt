package dev.bpmcrafters.processengineapi.adapter.c8.springboot.schedule

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationRunningEvent
import dev.bpmcrafters.processengineapi.springboot.lifecycle.ProcessApplicationStoppingEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ScheduledFuture

private val logger = KotlinLogging.logger {}

class ScheduledUserTaskDeliveryBinding(
  private val pullUserTaskDelivery: PullUserTaskDelivery,
  private val c8AdapterProperties: C8AdapterProperties,
  @param:Qualifier("c8-task-scheduler")
  private val c8taskScheduler: TaskScheduler
) {

  private var scheduledDelivery: ScheduledFuture<*>? = null

  @EventListener
  @Synchronized
  fun start(event: ProcessApplicationRunningEvent) {
    if (scheduledDelivery != null) {
      return
    }

    val deliveryInterval = Duration.of(c8AdapterProperties.userTasks.scheduleDeliveryFixedRateInSeconds, ChronoUnit.SECONDS)
    scheduledDelivery = c8taskScheduler.scheduleAtFixedRate(
      { refresh() },
      Instant.now().plus(deliveryInterval),
      deliveryInterval
    )
  }

  @EventListener
  @Synchronized
  fun stop(event: ProcessApplicationStoppingEvent) {
    scheduledDelivery?.cancel(false)
    scheduledDelivery = null
  }

  private fun refresh() {
    logger.trace { "PROCESS-ENGINE-C7-REMOTE-106: Delivering user tasks..." }
    pullUserTaskDelivery.refresh()
    logger.trace { "PROCESS-ENGINE-C7-REMOTE-107: Delivered user tasks." }
  }
}
