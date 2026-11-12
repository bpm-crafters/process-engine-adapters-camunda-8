package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.impl.task.filterBySubscription
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Uses task subscription available in the repository to subscribe to zeebe.
 */
class SubscribingServiceTaskDelivery @JvmOverloads constructor(
  private val camundaClient: CamundaClient,
  private val subscriptionRepository: SubscriptionRepository,
  private val workerId: String,
  private val retryTimeoutInSeconds: Long,
  private val lockDurationInSeconds: Long,
  /** Positive bound shared by all callback/opening waits in one cleanup attempt. */
  private val shutdownTimeout: Duration = Duration.ofSeconds(30)
) : AutoCloseable {

  init {
    require(!shutdownTimeout.isNegative && !shutdownTimeout.isZero) { "shutdownTimeout must be positive" }
    shutdownTimeout.toNanos() // Reject durations that cannot be represented by the monotonic deadline.
  }

  private class Registration {
    var accepting = true
    var worker: JobWorker? = null
    var settled = false
    var inFlight = 0
    var closingFailure: Throwable? = null
  }

  private val monitor = Object()
  private val registrations = mutableListOf<Registration>()
  private var closing = false
  private val callbackDepth = ThreadLocal.withInitial { 0 }
  private val openingDepth = ThreadLocal.withInitial { 0 }

  fun subscribe() {
    val subscriptions = subscriptionRepository.getTaskSubscriptions().filter { s -> s.taskType == TaskType.EXTERNAL }
    val batch = synchronized(monitor) {
      check(!closing && registrations.none { !it.accepting }) { "Service task cleanup is incomplete or in progress" }
      subscriptions.map { it to Registration().also { registration -> registrations.add(registration) } }
    }
    logger.trace {
      if (batch.isEmpty()) "PROCESS-ENGINE-C8-050: Not subscribing service tasks for subscriptions. No subscriptions found."
      else "PROCESS-ENGINE-C8-050: subscribing service tasks for subscriptions: $subscriptions"
    }
    openingDepth.set(openingDepth.get() + 1)
    var failure: Throwable? = null
    try {
      batch.forEach { (subscription, registration) ->
        try {
          if (synchronized(monitor) { registration.accepting }) {
            val worker = camundaClient.newWorker()
              .jobType(subscription.taskDescriptionKey)
              .handler { _, job -> deliver(registration, subscription, job) }
              .name(workerId)
              .forSubscription(subscription)
              .open()
            val closeLateWorker = synchronized(monitor) {
              if (registration.accepting) {
                registration.worker = worker
                false
              } else true
            }
            if (closeLateWorker) {
              try {
                worker.close()
              } catch (error: Throwable) {
                synchronized(monitor) { registration.closingFailure = error }
                throw error
              }
            }
          }
        } finally {
          synchronized(monitor) {
            registration.settled = true
            monitor.notifyAll()
          }
        }
      }
    } catch (error: Throwable) {
      failure = error
    } finally {
      // An opening failure must also release registrations whose open() was never attempted.
      synchronized(monitor) {
        batch.forEach { (_, registration) -> registration.settled = true }
        monitor.notifyAll()
      }
      openingDepth.set(openingDepth.get() - 1)
    }
    failure?.let { error ->
      try {
        close()
      } catch (cleanupError: Throwable) {
        if (cleanupError !== error) error.addSuppressed(cleanupError)
      }
      throw error
    }
  }

  private fun deliver(registration: Registration, subscription: TaskSubscriptionHandle, job: ActivatedJob) {
    synchronized(monitor) {
      if (!registration.accepting) return
      registration.inFlight++
    }
    callbackDepth.set(callbackDepth.get() + 1)
    try {
      consumeActivatedJob(subscription, job, camundaClient)
    } finally {
      callbackDepth.set(callbackDepth.get() - 1)
      synchronized(monitor) {
        registration.inFlight--
        monitor.notifyAll()
      }
    }
  }

  /**
   * Disables every registration before stopping acquisition, then drains the entire synchronous
   * callback (including repository and failure handling). Queued callbacks are ignored; their jobs
   * expire normally. Does not own the client, its executors, or application asynchronous work.
   * Timeout, interruption, concurrent cleanup and self-wait report failure rather than a false drain.
   * Incomplete registrations remain tracked for a later cleanup attempt.
   */
  override fun close() {
    val deadline = System.nanoTime() + shutdownTimeout.toNanos()
    val (snapshot, workersToClose) = synchronized(monitor) {
      check(!closing) { "Service task cleanup is already in progress" }
      closing = true
      val snapshot = registrations.toList()
      snapshot.forEach { it.accepting = false }
      snapshot to snapshot.mapNotNull { registration ->
        registration.worker.also { registration.worker = null }
      }
    }
    var failure: Throwable? = null
    fun record(error: Throwable) {
      val previous = failure
      if (previous == null) failure = error
      else if (previous !== error) previous.addSuppressed(error)
    }
    try {
      workersToClose.forEach { worker ->
        try {
          worker.close()
        } catch (error: Throwable) {
          record(error)
        }
      }
      try {
        synchronized(monitor) {
          check(callbackDepth.get() == 0 && openingDepth.get() == 0) {
            "Cannot drain service task callbacks from a callback or worker-opening thread"
          }
          while (snapshot.any { it.inFlight != 0 || !it.settled }) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Service task callbacks did not drain within $shutdownTimeout" }
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
          }
        }
      } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        record(error)
      } catch (error: Throwable) {
        record(error)
      }
    } finally {
      synchronized(monitor) {
        snapshot.forEach { registration -> registration.closingFailure?.let(::record) }
        registrations.removeAll { !it.accepting && it.inFlight == 0 && it.settled }
        closing = false
        monitor.notifyAll()
      }
    }
    failure?.let { throw it }
  }

  private fun consumeActivatedJob(activeSubscription: TaskSubscriptionHandle, job: ActivatedJob, camundaClient: CamundaClient) {
    if (activeSubscription.matches(job)) {
      val taskId = "${job.key}"
      subscriptionRepository.activateSubscriptionForTask(taskId, activeSubscription)
      val variables = job.variablesAsMap.filterBySubscription(activeSubscription)
      try {
        logger.debug { "PROCESS-ENGINE-C8-051: Delivering service task ${job.key}." }
        activeSubscription.action.accept(job.toTaskInformation().withReason(TaskInformation.CREATE), variables)
        logger.debug { "PROCESS-ENGINE-C8-052: Successfully delivered service task ${job.key}." }
      } catch (e: Exception) {
        logger.error(e) { "PROCESS-ENGINE-C8-051: Failing to deliver service task ${job.key}: ${e.message}." }
        try {
          camundaClient.newFailCommand(job.key).retries(job.retries.minus(1))
            .retryBackoff(Duration.ofSeconds(retryTimeoutInSeconds)).send().join() // could not deliver
        } finally {
          deactivateAndTerminate(taskId)
        }
        logger.error { "PROCESS-ENGINE-C8-052: Successfully failed to deliver service task ${job.key}: ${e.message}." }
      }
    } else {
      // put it back
      // TODO: check this, is it ok to put the job this way back?
      logger.trace { "PROCESS-ENGINE-C8-053: Received service task ${job.key} not matching subscriptions, returning it." }
      camundaClient.newFailCommand(job.key).retries(job.retries)
        .retryBackoff(Duration.ofSeconds(retryTimeoutInSeconds)).send().join()
      logger.trace { "PROCESS-ENGINE-C8-045: Successfully returned service task ${job.key} not matching subscriptions." }
    }

  }

  private fun deactivateAndTerminate(taskId: String) {
    subscriptionRepository.deactivateSubscriptionForTask(taskId)?.let {
      try {
        it.termination.accept(TaskInformation(taskId, mapOf()).withReason(TaskInformation.DELETE))
      } catch (terminationError: Exception) {
        logger.error(terminationError) { "PROCESS-ENGINE-C8-054: Failed to terminate service task ${taskId} during cleanup." }
      }
    }
  }

  /*
   * Additional restrictions to check.
   * The activated job can be completed by the Subscription strategy and is correct type (topic).
   */
  internal fun TaskSubscriptionHandle.matches(job: ActivatedJob): Boolean {
    return this.taskType == TaskType.EXTERNAL
      && (this.taskDescriptionKey == null || this.taskDescriptionKey == job.type)
      && this.restrictions
      .minus( // ignore some restrictions that are not relevant for external tasks or handled differntly
        CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS
      )
      .all {
      when (it.key) {
        CommonRestrictions.EXECUTION_ID -> it.value == "${job.elementInstanceKey}"
        CommonRestrictions.ACTIVITY_ID -> it.value == job.elementId
        CommonRestrictions.TENANT_ID -> it.value == job.tenantId
        CommonRestrictions.PROCESS_INSTANCE_ID -> it.value == "${job.processInstanceKey}"
        CommonRestrictions.PROCESS_DEFINITION_ID -> it.value == "${job.processDefinitionKey}"
        else -> false
      }
    }
    // job.customHeaders // FIXME: analyze this! user/service task, etc..
  }

  private fun JobWorkerBuilderStep3.forSubscription(subscription: TaskSubscriptionHandle): JobWorkerBuilderStep3 {
    // FIXME -> more to setup from props
    return this.apply {
      val payloadDescription = subscription.payloadDescription
      val tenantId = subscription.restrictions[CommonRestrictions.TENANT_ID]
      this.timeout(getLockDuration(subscription))
      if (tenantId != null) {
        this.tenantId(tenantId)
      }
      if (!payloadDescription.isNullOrEmpty()) {
        this.fetchVariables(payloadDescription.toList())
      }
    }
  }

  private fun getLockDuration(subscription: TaskSubscriptionHandle): Long {
    val customLockDuration = subscription.restrictions[CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS]
    return customLockDuration?.toLong() ?: (lockDurationInSeconds * 1000)
  }
}
