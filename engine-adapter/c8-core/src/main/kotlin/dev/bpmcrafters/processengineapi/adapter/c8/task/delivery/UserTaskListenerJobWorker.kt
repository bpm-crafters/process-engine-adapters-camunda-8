package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import io.camunda.client.CamundaClient
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.worker.JobWorker

/**
 * User task listener job worker.
 */
class UserTaskListenerJobWorker(
  private val camundaClient: CamundaClient,
  private val topic: String,
  private val workerId: String,
  private val maxJobsActive: Int,
  private val streamEnabled: Boolean,
  private val lockTimeInSeconds: Long,
  private val fetchVariables: List<String>?,
  private val handler: (ActivatedJob) -> Unit
) : AutoCloseable {

  private var jobWorker: JobWorker? = null

  fun open() {
    if (jobWorker?.isOpen == true) {
      return
    }

    jobWorker = camundaClient
      .newWorker()
      .jobType(topic)
      .handler { _, job -> handler(job) }
      .name(workerId)
      .maxJobsActive(maxJobsActive)
      .streamEnabled(streamEnabled)
      .timeout(lockTimeInSeconds * 1000)
      .apply {
        if (fetchVariables != null) {
          fetchVariables(fetchVariables)
        }
      }
      .open()
  }

  override fun close() {
    jobWorker?.close()
    jobWorker = null
  }
}
