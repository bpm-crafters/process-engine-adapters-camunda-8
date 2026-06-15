package dev.bpmcrafters.processengineapi.adapter.c8.springboot.subscription

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties
import io.camunda.client.CamundaClient
import io.camunda.client.api.command.ClientHttpException
import io.camunda.client.api.search.enums.GlobalTaskListenerEventType
import io.camunda.client.api.search.response.GlobalTaskListener
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Status

private val globalRegistrationLogger = KotlinLogging.logger {}

open class UserTaskListenerGlobalRegistration(
  private val camundaClient: CamundaClient,
  private val listenerProperties: C8AdapterProperties.UserTaskListener
) {

  open fun registerIfEnabled() {
    if (!listenerProperties.autoRegisterGlobalListener) {
      globalRegistrationLogger.trace { "PROCESS-ENGINE-C8-106: Global user task listener auto-registration is disabled." }
      return
    }

    try {
      val existingListener = findExistingListener()
      if (existingListener == null) {
        createGlobalListener()
      } else if (!existingListener.matchesExpectedConfiguration()) {
        globalRegistrationLogger.warn {
          "PROCESS-ENGINE-C8-107: Global user task listener ${listenerProperties.globalListenerId} already exists with different configuration. Leaving it unchanged."
        }
      } else {
        globalRegistrationLogger.debug {
          "PROCESS-ENGINE-C8-108: Global user task listener ${listenerProperties.globalListenerId} already exists."
        }
      }
    } catch (e: Exception) {
      throw IllegalStateException(
        "Failed to auto-register Camunda global user task listener '${listenerProperties.globalListenerId}'. Check Orchestration Cluster API permissions.",
        e
      )
    }
  }

  private fun findExistingListener(): GlobalTaskListener? =
    try {
      camundaClient
        .newGlobalTaskListenerGetRequest(listenerProperties.globalListenerId)
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
      "PROCESS-ENGINE-C8-109: Registering global user task listener ${listenerProperties.globalListenerId} for topic ${listenerProperties.topic}."
    }
    camundaClient
      .newCreateGlobalTaskListenerRequest()
      .id(listenerProperties.globalListenerId)
      .type(listenerProperties.topic)
      .eventTypes(listOf(GlobalTaskListenerEventType.ALL))
      .retries(listenerProperties.globalListenerRetries)
      .afterNonGlobal(listenerProperties.globalListenerAfterNonGlobal)
      .priority(listenerProperties.globalListenerPriority)
      .send()
      .join()
    globalRegistrationLogger.debug {
      "PROCESS-ENGINE-C8-110: Registered global user task listener ${listenerProperties.globalListenerId}."
    }
  }

  private fun GlobalTaskListener.matchesExpectedConfiguration(): Boolean =
    this.id == listenerProperties.globalListenerId
      && this.type == listenerProperties.topic
      && this.eventTypes?.contains(GlobalTaskListenerEventType.ALL) == true
      && this.retries == listenerProperties.globalListenerRetries
      && this.afterNonGlobal == listenerProperties.globalListenerAfterNonGlobal
      && this.priority == listenerProperties.globalListenerPriority
}
