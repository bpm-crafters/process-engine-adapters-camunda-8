package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.correlation.CorrelationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.correlation.SignalApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.decision.EvaluateDecisionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.deploy.DeploymentApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.process.StartProcessApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8CamundaClientUserTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.C8ExternalServiceTaskCompletionApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.FailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.completion.LinearMemoryFailureRetrySupplier
import dev.bpmcrafters.processengineapi.adapter.c8.task.modification.C8CamundaClientUserTaskModificationApiImpl
import dev.bpmcrafters.processengineapi.adapter.c8.task.subscription.C8TaskSubscriptionApiImpl
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi
import dev.bpmcrafters.processengineapi.correlation.SignalApi
import dev.bpmcrafters.processengineapi.decision.EvaluateDecisionApi
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.process.StartProcessApi
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi
import dev.bpmcrafters.processengineapi.task.UserTaskModificationApi
import io.camunda.client.CamundaClient
import io.quarkus.arc.Arc
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.enterprise.inject.literal.NamedLiteral
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class OverriddenAdapterTest {
  companion object {
    @JvmField
    @RegisterExtension
    val APPLICATION: QuarkusUnitTest = QuarkusUnitTest()
      .withApplicationRoot { archive -> archive.addClass(CustomAdapterProducer::class.java) }
      .overrideConfigKey("quarkus.process-engine-api.c8.enabled", "true")
      .overrideConfigKey("quarkus.process-engine-api.c8.service-tasks.delivery-strategy", "CUSTOM")
      .overrideConfigKey("quarkus.process-engine-api.c8.user-tasks.delivery-strategy", "SCHEDULED")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.service-tasks.worker-id", "test-worker")
      .overrideConfigKey("quarkus.camunda.devservices.enabled", "false")
      .overrideConfigKey("quarkus.camunda.resources.enabled", "false")
      .overrideRuntimeConfigKey("quarkus.camunda.active", "false")
  }

  @field:Inject lateinit var deploymentApis: Instance<DeploymentApi>
  @field:Inject lateinit var startProcessApis: Instance<StartProcessApi>

  @field:Inject
  @field:Named("custom-deployment")
  lateinit var customDeploymentApi: DeploymentApi

  @field:Inject
  @field:Named("custom-start")
  lateinit var customStartProcessApi: StartProcessApi

  @field:Inject lateinit var correlation: CorrelationApi
  @field:Inject lateinit var signal: SignalApi
  @field:Inject lateinit var decisions: EvaluateDecisionApi
  @field:Inject lateinit var subscriptions: TaskSubscriptionApi
  @field:Inject lateinit var serviceCompletion: ServiceTaskCompletionApi
  @field:Inject lateinit var userCompletion: UserTaskCompletionApi
  @field:Inject lateinit var modification: UserTaskModificationApi
  @field:Inject lateinit var repository: SubscriptionRepository
  @field:Inject lateinit var retries: FailureRetrySupplier

  @Test
  fun `application producers replace defaults`() {
    assertThat(deploymentApis.stream().toList()).containsExactly(customDeploymentApi)
    assertThat(startProcessApis.stream().toList()).containsExactly(customStartProcessApi)
  }

  @Test
  fun `application can replace all other APIs and collaborators`() {
    val beans = mapOf(
      CorrelationApi::class.java to "correlation", SignalApi::class.java to "signal",
      EvaluateDecisionApi::class.java to "decisions", TaskSubscriptionApi::class.java to "subscriptions",
      ServiceTaskCompletionApi::class.java to "service-completion", UserTaskCompletionApi::class.java to "user-completion",
      UserTaskModificationApi::class.java to "modification", SubscriptionRepository::class.java to "repository",
      FailureRetrySupplier::class.java to "retries"
    )
    beans.forEach { (type, name) ->
      assertThat(Arc.container().instance(type).bean.qualifiers).contains(NamedLiteral.of("custom-$name"))
    }
    assertThat(retries.apply("task-1").retryCount).isEqualTo(8)
  }

  @Dependent
  class CustomAdapterProducer {

    @Produces @Singleton @Named("custom-deployment")
    fun deploymentApi(client: CamundaClient): DeploymentApi = DeploymentApiImpl(client)

    @Produces @Singleton @Named("custom-start")
    fun startProcessApi(client: CamundaClient): StartProcessApi = StartProcessApiImpl(client)

    @Produces @Singleton @Named("custom-correlation")
    fun correlation(client: CamundaClient): CorrelationApi = CorrelationApiImpl(client)

    @Produces @Singleton @Named("custom-signal")
    fun signal(client: CamundaClient): SignalApi = SignalApiImpl(client)

    @Produces @Singleton @Named("custom-decisions")
    fun decisions(client: CamundaClient): EvaluateDecisionApi = EvaluateDecisionApiImpl(client)

    @Produces @Singleton @Named("custom-subscriptions")
    fun subscriptions(repository: SubscriptionRepository): TaskSubscriptionApi = C8TaskSubscriptionApiImpl(repository, null)

    @Produces @Singleton @Named("custom-service-completion")
    fun serviceCompletion(
      client: CamundaClient,
      repository: SubscriptionRepository,
      retries: FailureRetrySupplier
    ): ServiceTaskCompletionApi = C8ExternalServiceTaskCompletionApiImpl(client, repository, retries)

    @Produces @Singleton @Named("custom-user-completion")
    fun userCompletion(client: CamundaClient, repository: SubscriptionRepository): UserTaskCompletionApi =
      C8CamundaClientUserTaskCompletionApiImpl(client, repository)

    @Produces @Singleton @Named("custom-modification")
    fun modification(client: CamundaClient): UserTaskModificationApi = C8CamundaClientUserTaskModificationApiImpl(client)

    @Produces @Singleton @Named("custom-repository")
    fun repository(): SubscriptionRepository = InMemSubscriptionRepository()

    @Produces @Singleton @Named("custom-retries")
    fun retries(): FailureRetrySupplier = LinearMemoryFailureRetrySupplier(9, 17)
  }
}
