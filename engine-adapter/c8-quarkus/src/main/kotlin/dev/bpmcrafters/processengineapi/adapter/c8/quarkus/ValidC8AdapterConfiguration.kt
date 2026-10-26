package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.Companion.DEFAULT_PREFIX
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.ServiceTaskDeliveryStrategy
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.UserTaskDeliveryStrategy
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * Validates the adapter configuration once it is enabled: the properties that are only required then
 * — a plain `@NotNull` cannot express that, because the config mapping is built and validated on
 * every application start, also for a disabled adapter, which is why they are [java.util.Optional] —
 * and the value ranges.
 *
 * The checks live here rather than as constraints on the mapping methods on purpose. Constraints on
 * an interface method are inherited by every implementation of that interface, and Quarkus then
 * treats those methods as intercepted for method validation, which fails the build for any hand
 * written implementation an application may have, for instance a test fixture.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [C8AdapterConfigurationValidator::class])
annotation class ValidC8AdapterConfiguration(
  val message: String = "Invalid Camunda 8 process engine adapter configuration.",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = []
)

/**
 * Reports one violation per missing key, so a misconfigured application sees all of them at once
 * instead of one per restart.
 */
class C8AdapterConfigurationValidator : ConstraintValidator<ValidC8AdapterConfiguration, C8AdapterProperties> {

  override fun isValid(properties: C8AdapterProperties?, context: ConstraintValidatorContext): Boolean {
    if (properties == null || !properties.enabled()) {
      return true
    }
    val problems = missingKeys(properties).map {
      "The Camunda 8 process engine adapter is enabled but '$DEFAULT_PREFIX.$it' is not set."
    } + invalidValues(properties).map {
      "'$DEFAULT_PREFIX.${it.first}' is invalid: ${it.second}."
    }
    if (problems.isEmpty()) {
      return true
    }
    context.disableDefaultConstraintViolation()
    problems.forEach { context.buildConstraintViolationWithTemplate(it).addConstraintViolation() }
    return false
  }

  private fun missingKeys(properties: C8AdapterProperties): List<String> {
    val serviceTaskStrategy = properties.serviceTasks().deliveryStrategy()
    val userTaskStrategy = properties.userTasks().deliveryStrategy()
    return buildList {
      if (serviceTaskStrategy.isEmpty) {
        add("service-tasks.delivery-strategy")
      }
      if (userTaskStrategy.isEmpty) {
        add("user-tasks.delivery-strategy")
      }
      val workerIdRequired = serviceTaskStrategy.orElse(null) == ServiceTaskDeliveryStrategy.SUBSCRIPTION ||
        userTaskStrategy.orElse(null) == UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING
      if (workerIdRequired && properties.serviceTasks().workerId().isEmpty) {
        add("service-tasks.worker-id")
      }
    }
  }

  private fun invalidValues(properties: C8AdapterProperties): List<Pair<String, String>> {
    val serviceTasks = properties.serviceTasks()
    val userTasks = properties.userTasks()
    val listener = userTasks.listener()
    return buildList {
      atLeast("service-tasks.retries", serviceTasks.retries().toLong(), 0)
      atLeast("service-tasks.retry-timeout-in-seconds", serviceTasks.retryTimeoutInSeconds(), 0)
      atLeast("service-tasks.lock-time-in-seconds", serviceTasks.lockTimeInSeconds(), 1)
      atLeast("user-tasks.schedule-delivery-fixed-rate-in-seconds", userTasks.scheduleDeliveryFixedRateInSeconds(), 1)
      atLeast("user-tasks.listener.max-jobs-active", listener.maxJobsActive().toLong(), 1)
      atLeast("user-tasks.listener.lock-time-in-seconds", listener.lockTimeInSeconds(), 1)
      atLeast("user-tasks.listener.retry-timeout-in-seconds", listener.retryTimeoutInSeconds(), 0)
      atLeast("user-tasks.listener.global-listener-retries", listener.globalListenerRetries().toLong(), 0)
      notBlank("user-tasks.listener.topic", listener.topic())
      notBlank("user-tasks.listener.worker-id", listener.workerId())
      notBlank("user-tasks.listener.global-listener-id", listener.globalListenerId())
    }
  }

  private fun MutableList<Pair<String, String>>.atLeast(key: String, value: Long, minimum: Long) {
    if (value < minimum) {
      add(key to "must be at least $minimum but is $value")
    }
  }

  private fun MutableList<Pair<String, String>>.notBlank(key: String, value: String) {
    if (value.isBlank()) {
      add(key to "must not be blank")
    }
  }
}
