package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.ServiceTaskDeliveryStrategy
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.C8AdapterProperties.UserTaskDeliveryStrategy
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import io.camunda.client.CamundaClient
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class C8AdapterBindingsTest {

  private val camundaClient = mockk<CamundaClient>(relaxed = true)
  private val subscriptionRepository = InMemSubscriptionRepository()

  private fun bindings(properties: C8AdapterProperties) = C8AdapterBindings(
    camundaClient = camundaClient,
    subscriptionRepository = subscriptionRepository,
    properties = properties
  )

  @Test
  fun `scheduled strategy uses pull delivery without subscribing delivery`() {
    val bindings = bindings(testProperties(userTaskDeliveryStrategy = UserTaskDeliveryStrategy.SCHEDULED))
    assertThat(bindings.subscribingUserTaskDelivery).isNull()
    assertThat(bindings.refreshableUserTaskDelivery).isInstanceOf(PullUserTaskDelivery::class.java)
  }

  @Test
  fun `subscription refreshing strategy uses refreshing delivery for subscription and refresh`() {
    val bindings = bindings(testProperties(userTaskDeliveryStrategy = UserTaskDeliveryStrategy.SUBSCRIPTION_REFRESHING))
    assertThat(bindings.subscribingUserTaskDelivery).isInstanceOf(SubscribingRefreshingZeebeJobUserTaskDelivery::class.java)
    assertThat(bindings.refreshableUserTaskDelivery).isSameAs(bindings.subscribingUserTaskDelivery)
  }

  @Test
  fun `listener strategy uses listener delivery without refreshable delivery`() {
    val bindings = bindings(testProperties(userTaskDeliveryStrategy = UserTaskDeliveryStrategy.LISTENER))
    assertThat(bindings.subscribingUserTaskDelivery).isInstanceOf(ListenerUserTaskDelivery::class.java)
    assertThat(bindings.refreshableUserTaskDelivery).isNull()
  }

  @Test
  fun `custom strategies build no deliveries and start is a no-op`() {
    val bindings = bindings(
      testProperties(
        serviceTaskDeliveryStrategy = ServiceTaskDeliveryStrategy.CUSTOM,
        userTaskDeliveryStrategy = UserTaskDeliveryStrategy.CUSTOM
      )
    )
    assertThat(bindings.subscribingUserTaskDelivery).isNull()
    assertThat(bindings.refreshableUserTaskDelivery).isNull()
    bindings.start()
    bindings.close()
    verify { camundaClient wasNot Called }
  }

  @Test
  fun `start subscribes service tasks without failing on empty subscription repository`() {
    val bindings = bindings(testProperties())
    assertThatCode { bindings.start() }.doesNotThrowAnyException()
  }

  @Test
  fun `start subscribes listener delivery`() {
    val bindings = bindings(testProperties(userTaskDeliveryStrategy = UserTaskDeliveryStrategy.LISTENER))
    assertThatCode {
      bindings.start()
      bindings.close()
    }.doesNotThrowAnyException()
    verify { camundaClient.newWorker() }
  }

  @Test
  fun `missing service task delivery strategy fails on start`() {
    val bindings = bindings(testProperties(serviceTaskDeliveryStrategy = null))
    assertThatThrownBy { bindings.start() }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("service-tasks.delivery-strategy")
  }
}
