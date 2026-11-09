package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import dev.bpmcrafters.processengineapi.correlation.CorrelationApi
import dev.bpmcrafters.processengineapi.correlation.SignalApi
import dev.bpmcrafters.processengineapi.decision.EvaluateDecisionApi
import dev.bpmcrafters.processengineapi.deploy.DeployBundleCommand
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.deploy.NamedResource
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.process.StartProcessApi
import dev.bpmcrafters.processengineapi.process.StartProcessByDefinitionCmd
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.UserTaskModificationApi
import io.camunda.client.CamundaClient
import io.camunda.client.api.search.enums.ProcessInstanceState
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Boots a Quarkus application with the adapter enabled against a Camunda instance provided by the
 * quarkus-camunda dev services and runs a full service task and user task round trip.
 */
@QuarkusTest
class C8QuarkusAdapterITest {

  @Inject
  lateinit var deploymentApi: DeploymentApi

  @Inject
  lateinit var startProcessApi: StartProcessApi

  @Inject
  lateinit var taskSubscriptionApi: TaskSubscriptionApi

  @Inject
  lateinit var serviceTaskCompletionApi: ServiceTaskCompletionApi

  @Inject
  lateinit var userTaskCompletionApi: UserTaskCompletionApi

  @Inject
  lateinit var userTaskModificationApi: UserTaskModificationApi

  @Inject
  lateinit var correlationApi: CorrelationApi

  @Inject
  lateinit var signalApi: SignalApi

  @Inject
  lateinit var evaluateDecisionApi: EvaluateDecisionApi

  @Inject
  lateinit var subscriptionRepository: SubscriptionRepository

  @Inject
  lateinit var properties: C8AdapterProperties

  @Inject
  lateinit var camundaClient: CamundaClient

  @Inject
  lateinit var processApplication: ITestProcessApplication

  @Test
  fun `provides configured adapter properties and api beans`() {
    assertThat(properties.enabled()).isTrue()
    assertThat(properties.serviceTasks().deliveryStrategy())
      .hasValue(C8AdapterProperties.ServiceTaskDeliveryStrategy.SUBSCRIPTION)
    assertThat(properties.serviceTasks().workerId()).hasValue("itest-worker")
    assertThat(properties.userTasks().deliveryStrategy())
      .hasValue(C8AdapterProperties.UserTaskDeliveryStrategy.SCHEDULED)
    assertThat(properties.userTasks().scheduleDeliveryFixedRateInSeconds()).isEqualTo(1L)

    assertThat(deploymentApi).isNotNull
    assertThat(startProcessApi).isNotNull
    assertThat(taskSubscriptionApi).isNotNull
    assertThat(serviceTaskCompletionApi).isNotNull
    assertThat(userTaskCompletionApi).isNotNull
    assertThat(userTaskModificationApi).isNotNull
    assertThat(correlationApi).isNotNull
    assertThat(signalApi).isNotNull
    assertThat(evaluateDecisionApi).isNotNull
    assertThat(subscriptionRepository).isNotNull
  }

  @Test
  fun `runs service task and user task round trip`() {
    deploymentApi.deploy(
      DeployBundleCommand(
        listOf(NamedResource.fromClasspath("bpmn/itest-process.bpmn")),
        null
      )
    ).get()

    val processInstance = startProcessApi.startProcess(
      StartProcessByDefinitionCmd("itest-process", mapOf("stringValue" to "hello"))
    ).get()

    await.atMost(Duration.ofSeconds(60)) untilAsserted {
      assertThat(processApplication.completedServiceTaskIds).isNotEmpty
      assertThat(processApplication.userTaskSupport.getAllTasks()).isNotEmpty
    }

    val userTask = processApplication.userTaskSupport.getAllTasks().first()
    userTaskCompletionApi.completeTask(
      CompleteTaskCmd(userTask.taskId, mapOf("userResult" to "done"))
    ).get()

    await.atMost(Duration.ofSeconds(60)) untilAsserted {
      val instances = camundaClient.newProcessInstanceSearchRequest()
        .filter { filter -> filter.processInstanceKey(processInstance.instanceId.toLong()) }
        .send()
        .join()
        .items()
      assertThat(instances).isNotEmpty
      assertThat(instances.first().state).isEqualTo(ProcessInstanceState.COMPLETED)
    }
  }
}
