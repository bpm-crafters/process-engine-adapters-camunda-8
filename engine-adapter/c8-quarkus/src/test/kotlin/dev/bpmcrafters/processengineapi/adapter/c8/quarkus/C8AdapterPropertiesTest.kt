package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class C8AdapterPropertiesTest {

  @Test
  fun `requireEnabled passes for enabled adapter`() {
    assertThatCode { testProperties(adapterEnabled = true).requireEnabled() }.doesNotThrowAnyException()
  }

  @Test
  fun `requireEnabled fails for disabled adapter naming the exact key`() {
    assertThatThrownBy { testProperties(adapterEnabled = false).requireEnabled() }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("dev.bpm-crafters.process-api.adapter.c8.enabled")
  }

  @Test
  fun `required accessors return configured values`() {
    val properties = testProperties()
    assertThat(properties.requiredServiceTaskDeliveryStrategy())
      .isEqualTo(C8AdapterProperties.ServiceTaskDeliveryStrategy.SUBSCRIPTION)
    assertThat(properties.requiredServiceTaskWorkerId()).isEqualTo("test-worker")
    assertThat(properties.requiredUserTaskDeliveryStrategy())
      .isEqualTo(C8AdapterProperties.UserTaskDeliveryStrategy.SCHEDULED)
  }

  @Test
  fun `missing service task delivery strategy fails naming the exact key`() {
    assertThatThrownBy { testProperties(serviceTaskDeliveryStrategy = null).requiredServiceTaskDeliveryStrategy() }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("dev.bpm-crafters.process-api.adapter.c8.service-tasks.delivery-strategy")
  }

  @Test
  fun `missing service task worker id fails naming the exact key`() {
    assertThatThrownBy { testProperties(serviceTaskWorkerId = null).requiredServiceTaskWorkerId() }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("dev.bpm-crafters.process-api.adapter.c8.service-tasks.worker-id")
  }

  @Test
  fun `missing user task delivery strategy fails naming the exact key`() {
    assertThatThrownBy { testProperties(userTaskDeliveryStrategy = null).requiredUserTaskDeliveryStrategy() }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("dev.bpm-crafters.process-api.adapter.c8.user-tasks.delivery-strategy")
  }
}
