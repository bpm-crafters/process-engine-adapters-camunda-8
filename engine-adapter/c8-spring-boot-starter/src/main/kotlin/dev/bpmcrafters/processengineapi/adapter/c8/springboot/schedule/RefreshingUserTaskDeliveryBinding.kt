package dev.bpmcrafters.processengineapi.adapter.c8.springboot.schedule

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.TaskScheduler
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ScheduledFuture

private val logger = KotlinLogging.logger {}

class RefreshingUserTaskDeliveryBinding(
  private val subscribingRefreshingZeebeJobUserTaskDelivery: SubscribingRefreshingZeebeJobUserTaskDelivery,
  private val c8AdapterProperties: C8AdapterProperties,
  @param:Qualifier("c8-task-scheduler")
  private val c8taskScheduler: TaskScheduler
) {

  private var scheduledDelivery: ScheduledFuture<*>? = null

  @Synchronized
  fun start() {
    if (scheduledDelivery != null) {
      return
    }

    val refreshInterval = Duration.of(c8AdapterProperties.userTasks.scheduleDeliveryFixedRateInSeconds, ChronoUnit.SECONDS)
    scheduledDelivery = c8taskScheduler.scheduleAtFixedRate(
      { refresh() },
      Instant.now().plus(refreshInterval),
      refreshInterval
    )
  }

  @Synchronized
  fun stop() {
    scheduledDelivery?.cancel(false)
    scheduledDelivery = null
  }

  private fun refresh() {
    logger.trace { "PROCESS-ENGINE-C7-REMOTE-104: Refreshing user tasks..." }
    subscribingRefreshingZeebeJobUserTaskDelivery.refresh()
    logger.trace { "PROCESS-ENGINE-C7-REMOTE-105: Refreshed user tasks." }
  }
}
