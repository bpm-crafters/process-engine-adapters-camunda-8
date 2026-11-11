package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import io.camunda.client.api.worker.JobWorker
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Registration-scoped admission shared by the two SDK user-task worker strategies. */
internal class UserTaskWorkerLifecycle(private val shutdownTimeout: Duration) : AutoCloseable {
  internal class Epoch(var stopped: Boolean = false)
  internal class Registration internal constructor(internal val epoch: Epoch) {
    var accepting = true
    internal var worker: JobWorker? = null
    internal var settled = false
    internal var inFlight = 0
    internal var closingWorkers = 0
    internal var closingFailure: Throwable? = null
  }

  val monitor = Object()
  private var epoch = Epoch()
  private val registrations = mutableSetOf<Registration>()
  private var closing = false
  private val callbackDepth = ThreadLocal.withInitial { 0 }
  private val openingDepth = ThreadLocal.withInitial { 0 }
  private val workerClosingDepth = ThreadLocal.withInitial { 0 }

  init {
    require(!shutdownTimeout.isNegative && !shutdownTimeout.isZero) { "shutdownTimeout must be positive" }
    shutdownTimeout.toNanos()
  }

  // Called under monitor together with the delivery's subscription registry updates.
  fun checkSubscriptionAllowed() {
    check(!closing && (!epoch.stopped || registrations.isEmpty())) {
      "User task cleanup is incomplete or in progress"
    }
    if (epoch.stopped) epoch = Epoch()
  }

  fun register(): Registration = Registration(epoch).also { registrations.add(it) }

  fun open(registration: Registration, factory: () -> JobWorker) {
    openingDepth.set(openingDepth.get() + 1)
    try {
      if (synchronized(monitor) { registration.accepting }) {
        val worker = factory()
        val late = synchronized(monitor) {
          if (registration.accepting) {
            registration.worker = worker
            false
          } else true
        }
        if (late) {
          try {
            worker.close()
          } catch (error: Throwable) {
            synchronized(monitor) { registration.closingFailure = error }
            throw error
          }
        }
      }
    } finally {
      openingDepth.set(openingDepth.get() - 1)
      settle(registration)
    }
  }

  fun settle(registration: Registration) = synchronized(monitor) {
    registration.settled = true
    prune()
    monitor.notifyAll()
  }

  /** Includes ordinary cancellation's job-return command in callback accounting. */
  fun deliver(registration: Registration, callback: (Boolean) -> Unit) {
    val accepting = synchronized(monitor) {
      if (registration.epoch.stopped) return
      registrations.add(registration)
      registration.inFlight++
      registration.accepting
    }
    callbackDepth.set(callbackDepth.get() + 1)
    try {
      callback(accepting)
    } finally {
      callbackDepth.set(callbackDepth.get() - 1)
      synchronized(monitor) {
        registration.inFlight--
        prune()
        monitor.notifyAll()
      }
    }
  }

  /** Stops acquisition without waiting for callbacks or pending open() calls. */
  fun cancel(batch: Collection<Registration>) {
    val workers = synchronized(monitor) {
      batch.forEach { it.accepting = false }
      batch.mapNotNull { registration ->
        registration.worker?.let { worker ->
          registration.worker = null
          registration.closingWorkers++
          registration to worker
        }
      }.also { prune() }
    }
    var failure: Throwable? = null
    workers.forEach { (registration, worker) ->
      workerClosingDepth.set(workerClosingDepth.get() + 1)
      try {
        worker.close()
      } catch (error: Throwable) {
        synchronized(monitor) { registration.closingFailure = error }
        failure = combine(failure, error)
      } finally {
        workerClosingDepth.set(workerClosingDepth.get() - 1)
        synchronized(monitor) {
          registration.closingWorkers--
          prune()
          monitor.notifyAll()
        }
      }
    }
    failure?.let { throw it }
  }

  /** Disables this generation permanently, then bounds waits for whole callbacks and openings. */
  override fun close() {
    val deadline = System.nanoTime() + shutdownTimeout.toNanos()
    val snapshot = synchronized(monitor) {
      check(!closing) { "User task cleanup is already in progress" }
      closing = true
      epoch.stopped = true
      registrations.toList().also { batch -> batch.forEach { it.accepting = false } }
    }
    var failure: Throwable? = null
    try {
      try {
        cancel(snapshot)
      } catch (error: Throwable) {
        failure = combine(failure, error)
      }
      try {
        synchronized(monitor) {
          check(callbackDepth.get() == 0 && openingDepth.get() == 0 && workerClosingDepth.get() == 0) {
            "Cannot drain user task callbacks from a callback or worker-opening thread, or a worker-closing thread"
          }
          while (snapshot.any { it.inFlight != 0 || !it.settled || it.closingWorkers != 0 }) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "User task callbacks did not drain within $shutdownTimeout" }
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
          }
        }
      } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        failure = combine(failure, error)
      } catch (error: Throwable) {
        failure = combine(failure, error)
      }
    } finally {
      synchronized(monitor) {
        snapshot.forEach { registration ->
          registration.closingFailure?.let { failure = combine(failure, it) }
          registration.closingFailure = null
        }
        prune()
        closing = false
        monitor.notifyAll()
      }
    }
    failure?.let { throw it }
  }

  private fun prune() {
    registrations.removeAll {
      !it.accepting && it.worker == null && it.settled && it.inFlight == 0 && it.closingWorkers == 0 && it.closingFailure == null
    }
  }

  private fun combine(previous: Throwable?, error: Throwable): Throwable {
    if (previous == null) return error
    if (previous !== error && previous.suppressed.none { it === error }) previous.addSuppressed(error)
    return previous
  }
}
