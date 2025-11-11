package dev.bpmcrafters.processengineapi.adapter.c8

import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import io.camunda.zeebe.gateway.protocol.GatewayGrpc
import io.grpc.ClientInterceptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * This test demonstrates that when camunda.client.defaults.stream-enabled=true is configured,
 * the job workers created by the adapter should respect this configuration.
 */
@SpringBootTest
@Import(C8CamundaClientConfigurationITest.TestConfiguration::class)
@ActiveProfiles("itest")
@TestPropertySource(
  properties = [
    "camunda.client.worker.defaults.stream-enabled=true"
  ]
)
class C8CamundaClientConfigurationITest {

  class TestConfiguration {

    /* Initialize subscriptions and prepare a list to capture commands sent to the server */
    @Bean("commandsList")
    fun initSubscriptionsAndPrepareCommandList(subscriptionRepository: SubscriptionRepository): ArrayList<String> {
      subscriptionsInitHook(subscriptionRepository)
      return java.util.ArrayList()
    }

    /* A gRPC interceptor that captures the commands sent to the server */
    @Bean("diagnostic-interceptor")
    fun diagnosticGrpcInterceptor(commandsCaptor: ArrayList<String>): ClientInterceptor = object : ClientInterceptor {
      override fun <ReqT, RespT> interceptCall(
        method: io.grpc.MethodDescriptor<ReqT, RespT>,
        callOptions: io.grpc.CallOptions,
        next: io.grpc.Channel
      ): io.grpc.ClientCall<ReqT, RespT> {
        commandsCaptor.add(method.fullMethodName!!)
        return next.newCall(method, callOptions)
      }
    }

    private fun subscriptionsInitHook(subscriptionRepository: SubscriptionRepository) {
      subscriptionRepository.createTaskSubscription(
        TaskSubscriptionHandle(
          taskType = dev.bpmcrafters.processengineapi.task.TaskType.EXTERNAL,
          taskDescriptionKey = "test-external-task",
          action = { _, _ -> },
          restrictions = mapOf(),
          payloadDescription = null,
          termination = { _ -> }
        ))
    }
  }

  @Autowired
  @Qualifier("commandsList")
  private lateinit var commandsSentToServer: ArrayList<String>

  @Test
  fun `autoconfigured job workers should obey Camunda client's configuration and use job streaming if enabled`() {
    // Given the configuration camunda.client.defaults.stream-enabled=true
    // And a subscription for an external task exists
    // Then autoconfiguration would create and start job workers with job streaming request sent to the server
    assertThat(commandsSentToServer.contains(GatewayGrpc.getStreamActivatedJobsMethod().fullMethodName)).isTrue()

  }
}
