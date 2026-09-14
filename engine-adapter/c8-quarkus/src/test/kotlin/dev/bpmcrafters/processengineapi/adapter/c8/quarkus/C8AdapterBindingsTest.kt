package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.GlobalUserTaskListenerRegistrationHelper
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.ListenerUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingRefreshingZeebeJobUserTaskDelivery
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Which deliveries exist is decided by the bean conditions on [C8TaskDeliveryProducers] and is
 * covered by [C8TaskDeliveryProducersITest]. Here the handles are stubbed, so these tests pin what
 * the bindings do with the deliveries they are given.
 */
class C8AdapterBindingsTest {

  private val serviceTaskDelivery = mockk<SubscribingServiceTaskDelivery>(relaxed = true)
  private val refreshingUserTaskDelivery = mockk<SubscribingRefreshingZeebeJobUserTaskDelivery>(relaxed = true)
  private val pullUserTaskDelivery = mockk<PullUserTaskDelivery>(relaxed = true)
  private val listenerUserTaskDelivery = mockk<ListenerUserTaskDelivery>(relaxed = true)
  private val globalListenerRegistration = mockk<GlobalUserTaskListenerRegistrationHelper>(relaxed = true)
  private val listenerPreloadDelivery = mockk<PullUserTaskDelivery>(relaxed = true)

  private fun <T : Any> handle(value: T?): Instance<T> = mockk {
    every { isResolvable } returns (value != null)
    if (value != null) {
      every { get() } returns value
    }
  }

  private fun bindings(
    serviceTask: SubscribingServiceTaskDelivery? = null,
    refreshing: SubscribingRefreshingZeebeJobUserTaskDelivery? = null,
    pull: PullUserTaskDelivery? = null,
    listener: ListenerUserTaskDelivery? = null,
    preloadExistingTasks: Boolean = true
  ) = C8AdapterBindings(
    serviceTaskDelivery = handle(serviceTask),
    refreshingUserTaskDelivery = handle(refreshing),
    pullUserTaskDelivery = handle(pull),
    listenerUserTaskDelivery = handle(listener),
    globalUserTaskListenerRegistrationHelper = handle(if (listener != null) globalListenerRegistration else null),
    listenerPreloadDelivery = handle(if (listener != null) listenerPreloadDelivery else null),
    properties = testProperties(preloadExistingTasks = preloadExistingTasks)
  )

  @Test
  fun `subscribes the produced service task delivery`() {
    bindings(serviceTask = serviceTaskDelivery).start()

    verify { serviceTaskDelivery.subscribe() }
  }

  @Test
  fun `custom strategies produce no delivery and start is a no-op`() {
    val bindings = bindings()

    assertThat(bindings.hasServiceTaskDelivery()).isFalse()
    assertThat(bindings.hasUserTaskDelivery()).isFalse()
    assertThatCode {
      bindings.start()
      bindings.close()
    }.doesNotThrowAnyException()
    verify { serviceTaskDelivery wasNot Called }
    verify { listenerUserTaskDelivery wasNot Called }
  }

  @Test
  fun `subscribes the refreshing user task delivery`() {
    val bindings = bindings(refreshing = refreshingUserTaskDelivery)

    assertThat(bindings.hasUserTaskDelivery()).isTrue()
    bindings.start()

    verify { refreshingUserTaskDelivery.subscribe() }
    verify { listenerUserTaskDelivery wasNot Called }
  }

  @Test
  fun `scheduled strategy reports a user task delivery without subscribing anything`() {
    val bindings = bindings(pull = pullUserTaskDelivery)

    assertThat(bindings.hasUserTaskDelivery()).isTrue()
    bindings.start()

    verify { pullUserTaskDelivery wasNot Called }
  }

  @Test
  fun `listener strategy registers the global listener, preloads and subscribes`() {
    bindings(listener = listenerUserTaskDelivery).start()

    verify { globalListenerRegistration.registerIfEnabled() }
    verify { listenerPreloadDelivery.refresh() }
    verify { listenerUserTaskDelivery.subscribe() }
  }

  @Test
  fun `listener strategy skips the preload when it is disabled`() {
    bindings(listener = listenerUserTaskDelivery, preloadExistingTasks = false).start()

    verify { listenerPreloadDelivery wasNot Called }
    verify { listenerUserTaskDelivery.subscribe() }
  }

  @Test
  fun `a failing preload does not prevent the listener subscription`() {
    every { listenerPreloadDelivery.refresh() } throws IllegalStateException("boom")

    assertThatCode { bindings(listener = listenerUserTaskDelivery).start() }.doesNotThrowAnyException()

    verify { listenerUserTaskDelivery.subscribe() }
  }

  @Test
  fun `close closes a subscribed listener delivery`() {
    val bindings = bindings(listener = listenerUserTaskDelivery)
    bindings.start()

    bindings.close()

    verify { listenerUserTaskDelivery.close() }
  }

  @Test
  fun `close without start closes nothing`() {
    val bindings = bindings(listener = listenerUserTaskDelivery)

    assertThatCode { bindings.close() }.doesNotThrowAnyException()

    verify(exactly = 0) { listenerUserTaskDelivery.close() }
  }
}
