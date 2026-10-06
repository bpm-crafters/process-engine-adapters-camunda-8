package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.Companion.DEFAULT_PREFIX
import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.RefreshableDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boots ArC per delivery strategy and asserts which delivery beans the conditions on
 * [C8TaskDeliveryProducers] make available. Only a real container can cover that, but no broker is
 * needed: the adapter is disabled, so the lifecycle never subscribes, the quarkus-camunda client is
 * switched to its no-op implementation, and `isResolvable` answers without instantiating the bean.
 */
abstract class AbstractDeliveryProducersTest {

  @Inject
  lateinit var serviceTaskDelivery: Instance<SubscribingServiceTaskDelivery>

  @Inject
  lateinit var refreshingUserTaskDelivery: Instance<SubscribingRefreshingZeebeJobUserTaskDelivery>

  @Inject
  lateinit var pullUserTaskDelivery: Instance<PullUserTaskDelivery>

  @Inject
  lateinit var listenerUserTaskDelivery: Instance<ListenerUserTaskDelivery>

  @Inject
  lateinit var globalListenerRegistration: Instance<GlobalUserTaskListenerRegistrationHelper>

  @Inject
  @field:ListenerPreload
  lateinit var listenerPreloadDelivery: Instance<PullUserTaskDelivery>

  @Inject
  lateinit var subscribingUserTaskDelivery: Instance<SubscribingUserTaskDelivery>

  @Inject
  lateinit var refreshableUserTaskDelivery: Instance<RefreshableDelivery>
}

/**
 * Base profile: no broker, no dev services, adapter inert. The test process application is excluded
 * because its startup observer resolves adapter api beans that a disabled adapter refuses.
 */
abstract class DeliveryProfile(private val serviceTaskStrategy: String, private val userTaskStrategy: String) :
  QuarkusTestProfile {
  override fun getConfigOverrides(): Map<String, String> = mapOf(
    "quarkus.camunda.devservices.enabled" to "false",
    // no broker is needed: the adapter stays disabled and a no-op client keeps startup offline
    "quarkus.camunda.active" to "false",
    "quarkus.arc.exclude-types" to ITestProcessApplication::class.java.name,
    "$DEFAULT_PREFIX.enabled" to "false",
    "$DEFAULT_PREFIX.service-tasks.delivery-strategy" to serviceTaskStrategy,
    "$DEFAULT_PREFIX.service-tasks.worker-id" to "itest-worker",
    "$DEFAULT_PREFIX.user-tasks.delivery-strategy" to userTaskStrategy
  )
}

class SubscriptionScheduledProfile : DeliveryProfile("SUBSCRIPTION", "SCHEDULED")
class ListenerProfile : DeliveryProfile("SUBSCRIPTION", "LISTENER")
class HyphenatedProfile : DeliveryProfile("subscription", "subscription-refreshing")
class CustomProfile : DeliveryProfile("CUSTOM", "CUSTOM")

@QuarkusTest
@TestProfile(SubscriptionScheduledProfile::class)
class SubscriptionAndScheduledDeliveryITest : AbstractDeliveryProducersTest() {

  @Test
  fun `produces the subscribing service task and the pull user task delivery`() {
    assertThat(serviceTaskDelivery.isResolvable).isTrue()
    assertThat(pullUserTaskDelivery.isResolvable).isTrue()
    assertThat(refreshableUserTaskDelivery.isResolvable).isTrue()

    assertThat(refreshingUserTaskDelivery.isResolvable).isFalse()
    assertThat(listenerUserTaskDelivery.isResolvable).isFalse()
    assertThat(listenerPreloadDelivery.isResolvable).isFalse()
    assertThat(subscribingUserTaskDelivery.isResolvable).isFalse()
  }
}

@QuarkusTest
@TestProfile(ListenerProfile::class)
class ListenerDeliveryITest : AbstractDeliveryProducersTest() {

  @Test
  fun `produces the listener delivery, its global registration and a qualified preload delivery`() {
    assertThat(listenerUserTaskDelivery.isResolvable).isTrue()
    assertThat(globalListenerRegistration.isResolvable).isTrue()
    assertThat(listenerPreloadDelivery.isResolvable).isTrue()
    assertThat(subscribingUserTaskDelivery.isResolvable).isTrue()

    assertThat(refreshingUserTaskDelivery.isResolvable).isFalse()
  }

  @Test
  fun `the qualified preload delivery is not offered to the fixed rate refresh`() {
    assertThat(pullUserTaskDelivery.isResolvable).isFalse()
    assertThat(refreshableUserTaskDelivery.isResolvable).isFalse()
  }
}

@QuarkusTest
@TestProfile(HyphenatedProfile::class)
class HyphenatedStrategyDeliveryITest : AbstractDeliveryProducersTest() {

  /**
   * The config mapping accepts `subscription` and `subscription-refreshing`, so the bean conditions
   * have to as well — an equality match on the raw value would silently produce no delivery.
   */
  @Test
  fun `lower case and hyphenated strategy values still produce the deliveries`() {
    assertThat(serviceTaskDelivery.isResolvable).isTrue()
    assertThat(refreshingUserTaskDelivery.isResolvable).isTrue()
    assertThat(subscribingUserTaskDelivery.isResolvable).isTrue()
    assertThat(refreshableUserTaskDelivery.isResolvable).isTrue()
  }

  @Test
  fun `the refreshing delivery serves both roles as one instance`() {
    assertThat(refreshableUserTaskDelivery.get()).isSameAs(subscribingUserTaskDelivery.get())
  }
}

@QuarkusTest
@TestProfile(CustomProfile::class)
class CustomStrategyDeliveryITest : AbstractDeliveryProducersTest() {

  @Test
  fun `custom strategies produce no delivery at all`() {
    assertThat(serviceTaskDelivery.isResolvable).isFalse()
    assertThat(refreshingUserTaskDelivery.isResolvable).isFalse()
    assertThat(pullUserTaskDelivery.isResolvable).isFalse()
    assertThat(listenerUserTaskDelivery.isResolvable).isFalse()
    assertThat(listenerPreloadDelivery.isResolvable).isFalse()
    assertThat(subscribingUserTaskDelivery.isResolvable).isFalse()
    assertThat(refreshableUserTaskDelivery.isResolvable).isFalse()
  }
}
