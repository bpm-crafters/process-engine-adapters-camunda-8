package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import io.camunda.client.CamundaClient
import io.camunda.client.api.command.ClientHttpException
import io.camunda.client.api.search.enums.GlobalTaskListenerEventType
import io.camunda.client.api.search.response.GlobalTaskListener
import io.github.oshai.kotlinlogging.KotlinLogging

private val globalRegistrationLogger = KotlinLogging.logger {}

open class UserTaskListenerGlobalRegistration(
  private val camundaClient: CamundaClient,
  private val autoRegisterGlobalListener: Boolean,
  private val globalListenerId: String,
  private val topic: String,
  private val globalListenerRetries: Int,
  private val globalListenerAfterNonGlobal: Boolean,
  private val globalListenerPriority: Int,
) {

  open fun registerIfEnabled() {
    if (!autoRegisterGlobalListener) {
      globalRegistrationLogger.trace { "PROCESS-ENGINE-C8-106: Global user task listener auto-registration is disabled." }
      return
    }

    try {
      val existingListener = findExistingListener()
      if (existingListener == null) {
        createGlobalListener()
      } else if (!existingListener.matchesExpectedConfiguration()) {
        globalRegistrationLogger.warn {
          "PROCESS-ENGINE-C8-107: Global user task listener $globalListenerId already exists with different configuration. Leaving it unchanged."
        }
      } else {
        globalRegistrationLogger.debug {
          "PROCESS-ENGINE-C8-108: Global user task listener $globalListenerId already exists."
        }
      }
    } catch (e: Exception) {
      throw IllegalStateException(
        "Failed to auto-register Camunda global user task listener '$globalListenerId'. Check Orchestration Cluster API permissions.",
        e
      )
    }
  }

  private fun findExistingListener(): GlobalTaskListener? =
    try {
      camundaClient
        .newGlobalTaskListenerGetRequest(globalListenerId)
        .send()
        .join()
    } catch (e: Exception) {
      if ((e is ClientHttpException && e.code() == 404)
        || (e.cause is ClientHttpException && (e.cause as ClientHttpException).code() == 404)
      ) {
        null
      } else {
        throw e
      }
    }

  private fun createGlobalListener() {
    globalRegistrationLogger.debug {
      "PROCESS-ENGINE-C8-109: Registering global user task listener $globalListenerId for topic $topic."
    }
    camundaClient
      .newCreateGlobalTaskListenerRequest()
      .id(globalListenerId)
      .type(topic)
      .eventTypes(listOf(GlobalTaskListenerEventType.ALL))
      .retries(globalListenerRetries)
      .afterNonGlobal(globalListenerAfterNonGlobal)
      .priority(globalListenerPriority)
      .send()
      .join()
    globalRegistrationLogger.debug {
      "PROCESS-ENGINE-C8-110: Registered global user task listener $globalListenerId."
    }
  }

  private fun GlobalTaskListener.matchesExpectedConfiguration(): Boolean =
    this.id == globalListenerId
      && this.type == topic
      && this.eventTypes?.contains(GlobalTaskListenerEventType.ALL) == true
      && this.retries == globalListenerRetries
      && this.afterNonGlobal == globalListenerAfterNonGlobal
      && this.priority == globalListenerPriority
}
