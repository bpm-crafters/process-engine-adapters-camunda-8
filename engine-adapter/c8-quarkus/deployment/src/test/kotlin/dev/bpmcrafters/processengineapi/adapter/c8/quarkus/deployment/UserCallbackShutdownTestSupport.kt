package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskType
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.CompleteJobCommandStep1
import io.camunda.client.api.command.UpdateTimeoutJobCommandStep1
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.response.CompleteJobResponse
import io.camunda.client.api.response.UpdateTimeoutJobResponse
import io.camunda.client.api.response.UserTaskProperties
import io.camunda.client.api.search.enums.JobKind
import io.camunda.client.api.search.enums.ListenerEventType
import io.camunda.client.api.worker.JobHandler
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.Config
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object UserCallbackShutdownTestSupport {
  fun application(listener: Boolean): QuarkusUnitTest {
    val events = Path.of(System.getProperty("java.io.tmpdir"), "c8-user-shutdown-${UUID.randomUUID()}.events")
    return QuarkusUnitTest()
      .withApplicationRoot { it.addClasses(
        UserCallbackShutdownTestSupport::class.java, UserShutdownProbe::class.java, UserShutdownClientProducer::class.java
      ) }
      .overrideConfigKey("quarkus.process-engine-api.c8.enabled", "true")
      .overrideConfigKey("quarkus.process-engine-api.c8.service-tasks.delivery-strategy", "CUSTOM")
      .overrideConfigKey("quarkus.process-engine-api.c8.user-tasks.delivery-strategy",
        if (listener) "LISTENER" else "SUBSCRIPTION_REFRESHING")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.service-tasks.worker-id", "shutdown-test")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.user-tasks.listener.preload-existing-tasks", "false")
      .overrideRuntimeConfigKey("test.c8.user-shutdown-file", events.toString())
      .overrideRuntimeConfigKey("test.c8.listener", listener.toString())
      .setBeforeAllCustomizer { Files.createFile(events) }
      .setAfterUndeployListener {
        assertThat(Files.readAllLines(events)).containsExactly(
          "callback-entered", "worker-closed", "drain-observed", "callback-finished", "application-shutdown", "client-disposed"
        )
      }
      .setAfterAllCustomizer { Files.deleteIfExists(events) }
  }
}

@Singleton
class UserShutdownProbe @Inject constructor(config: Config, private val repository: SubscriptionRepository) {
  private val events = Path.of(config.getValue("test.c8.user-shutdown-file", String::class.java))
  val listener = config.getValue("test.c8.listener", Boolean::class.java)
  lateinit var handler: JobHandler
  lateinit var client: CamundaClient
  private val entered = CountDownLatch(1)
  private val release = CountDownLatch(1)
  val workerClosed = CountDownLatch(1)
  private val laterObserver = CountDownLatch(1)
  private val actions = AtomicInteger()
  private val callbacks = Executors.newSingleThreadExecutor()
  private val witness = Executors.newSingleThreadExecutor()
  private lateinit var callback: Future<*>
  private lateinit var queued: Future<*>
  private lateinit var witnessed: Future<*>
  private lateinit var job: ActivatedJob
  private var setupFailure: Throwable? = null

  @Synchronized
  fun record(event: String) {
    Files.writeString(events, "$event\n", APPEND)
  }

  fun register(@Observes event: StartupEvent) {
    repository.createTaskSubscription(TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ ->
        actions.incrementAndGet()
        record("callback-entered")
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "callback release timed out" }
        check(laterObserver.count == 1L) { "application shutdown overtook callback" }
        record("callback-finished")
      },
      termination = { _ -> }
    ))
  }

  fun startCallback() {
    try {
      startCallbackAndWitness()
    } catch (failure: Throwable) {
      setupFailure = failure
      try {
        cleanupExecutors()
      } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
      }
      throw failure
    }
  }

  private fun startCallbackAndWitness() {
    val userTask = mock<UserTaskProperties> { whenever(it.userTaskKey).thenReturn(5678L) }
    job = mock {
      whenever(it.key).thenReturn(1234L)
      whenever(it.elementId).thenReturn("user-task")
      whenever(it.variablesAsMap).thenReturn(emptyMap())
      whenever(it.customHeaders).thenReturn(emptyMap())
      whenever(it.kind).thenReturn(JobKind.TASK_LISTENER)
      whenever(it.listenerEventType).thenReturn(ListenerEventType.CREATING)
      whenever(it.userTask).thenReturn(userTask)
    }
    callback = callbacks.submit { handler.handle(mock(), job) }
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
    queued = callbacks.submit { handler.handle(mock(), job) }
    witnessed = witness.submit {
      try {
        check(workerClosed.await(5, TimeUnit.SECONDS)) { "worker was not closed" }
        check(!laterObserver.await(200, TimeUnit.MILLISECONDS)) { "later observer ran before callback drain" }
        record("drain-observed")
      } catch (failure: Throwable) {
        record("drain-witness-failed")
        throw failure
      } finally {
        release.countDown()
      }
    }
  }

  // Default priority must follow the adapter's shutdown observer, on natural Quarkus teardown.
  fun shutdown(@Observes event: ShutdownEvent) {
    var primaryFailure = setupFailure
    try {
      // A test-class/setup failure may prevent the body from starting the callback harness.
      if (!::witnessed.isInitialized || setupFailure != null) return
      laterObserver.countDown()
      witnessed.get(5, TimeUnit.SECONDS)
      callback.get(5, TimeUnit.SECONDS)
      queued.get(5, TimeUnit.SECONDS)
      callbacks.submit { handler.handle(mock(), job) }.get(5, TimeUnit.SECONDS)
      assertThat(actions.get()).isEqualTo(1)
      verify(client, times(if (listener) 1 else 0)).newCompleteCommand(any<Long>())
      verify(client, never()).newFailCommand(any<Long>())
      verify(client, never()).close()
      record("application-shutdown")
    } catch (failure: Throwable) {
      primaryFailure = failure
      record("shutdown-assertion-failed")
      throw failure
    } finally {
      try {
        cleanupExecutors()
      } catch (cleanupFailure: Throwable) {
        val primary = primaryFailure
        if (primary == null) throw cleanupFailure
        if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
      }
    }
  }

  private fun cleanupExecutors() {
    release.countDown()
    callbacks.shutdownNow()
    witness.shutdownNow()
    check(callbacks.awaitTermination(5, TimeUnit.SECONDS)) { "callback executor did not terminate" }
    check(witness.awaitTermination(5, TimeUnit.SECONDS)) { "witness executor did not terminate" }
  }
}

@Singleton
class UserShutdownClientProducer {
  @Produces
  @Singleton
  fun client(probe: UserShutdownProbe): CamundaClient {
    val client = mock<CamundaClient>()
    probe.client = client
    val first = mock<JobWorkerBuilderStep1>()
    val second = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep2>()
    val third = mock<JobWorkerBuilderStep1.JobWorkerBuilderStep3>(defaultAnswer = RETURNS_SELF)
    val worker = mock<JobWorker>()
    whenever(client.newWorker()).thenReturn(first)
    whenever(first.jobType(any())).thenReturn(second)
    whenever(second.handler(any())).thenAnswer { probe.handler = it.getArgument(0); third }
    whenever(third.open()).thenReturn(worker)
    doAnswer {
      probe.record("worker-closed")
      probe.workerClosed.countDown()
      null
    }.whenever(worker).close()
    val complete = mock<CompleteJobCommandStep1>()
    val completed = mock<CamundaFuture<CompleteJobResponse>>()
    whenever(client.newCompleteCommand(any<Long>())).thenReturn(complete)
    whenever(complete.send()).thenReturn(completed)
    val update = mock<UpdateTimeoutJobCommandStep1>()
    val update2 = mock<UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2>(defaultAnswer = RETURNS_SELF)
    val updated = mock<CamundaFuture<UpdateTimeoutJobResponse>>()
    whenever(client.newUpdateTimeoutCommand(any<Long>())).thenReturn(update)
    whenever(update.timeout(any<Long>())).thenReturn(update2)
    whenever(update2.send()).thenReturn(updated)
    return client
  }

  fun dispose(@Disposes client: CamundaClient, probe: UserShutdownProbe) {
    verify(client, never()).close()
    client.close()
    probe.record("client-disposed")
  }
}
