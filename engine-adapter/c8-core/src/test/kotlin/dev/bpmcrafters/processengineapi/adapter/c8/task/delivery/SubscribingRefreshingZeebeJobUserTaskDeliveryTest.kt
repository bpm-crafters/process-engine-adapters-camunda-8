package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.adapter.c8.task.subscription.C8TaskSubscriptionApiImpl
import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository
import dev.bpmcrafters.processengineapi.impl.task.TaskSubscriptionHandle
import dev.bpmcrafters.processengineapi.task.TaskHandler
import dev.bpmcrafters.processengineapi.task.TaskInformation
import dev.bpmcrafters.processengineapi.task.TaskType
import dev.bpmcrafters.processengineapi.task.UnsubscribeFromTaskCmd
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.ProblemDetail
import io.camunda.client.api.command.ClientStatusException
import io.camunda.client.api.command.FailJobCommandStep1
import io.camunda.client.api.command.ProblemException
import io.camunda.client.api.command.UpdateTimeoutJobCommandStep1
import io.camunda.client.api.response.FailJobResponse
import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.response.UpdateTimeoutJobResponse
import io.camunda.client.api.worker.JobClient
import io.camunda.client.api.worker.JobHandler
import io.camunda.client.api.worker.JobWorker
import io.camunda.client.api.worker.JobWorkerBuilderStep1
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3
import io.camunda.zeebe.protocol.Protocol
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SubscribingRefreshingZeebeJobUserTaskDeliveryTest {

  companion object {
    private const val JOB_KEY = 9876L
    private const val TASK_ID = "9876"
    private const val ELEMENT_ID = "user-task-1"
    private const val TENANT_ID = "tenant-1"
    private const val PROCESS_INSTANCE_KEY = 333L
    private const val PROCESS_DEFINITION_KEY = 444L
    private const val BPMN_PROCESS_ID = "simple-process"
    private const val RETRIES = 3
  }

  // We need an instance of SubscribingRefreshingUserTaskDelivery because matches is an extension function
  // on TaskSubscriptionHandle declared inside SubscribingRefreshingUserTaskDelivery class.
  private val delivery = SubscribingRefreshingZeebeJobUserTaskDelivery(
    camundaClient = mock(),
    subscriptionRepository = InMemSubscriptionRepository(),
    workerId = "test-worker",
    userTaskLockTimeoutMs = 60000L
  )

  @Test
  fun `should match when all restrictions match`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task-1",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "user-task-1",
        CommonRestrictions.TENANT_ID to "tenant-1"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("user-task-1")
      whenever(it.tenantId).thenReturn("tenant-1")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should match and ignore workerLockDurationInMilliseconds restriction`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task-1",
      restrictions = mapOf(
        CommonRestrictions.ACTIVITY_ID to "user-task-1",
        CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS to "5000"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("user-task-1")
    }

    with(delivery) {
      // It should match even if workerLockDurationInMilliseconds is present in restrictions
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should not match when task type is different`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.EXTERNAL,
      taskDescriptionKey = "user-task-1",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("user-task-1")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should not match when elementId is different`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task-1",
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("other-user-task")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should not match when restriction does not match`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = "user-task-1",
      restrictions = mapOf(
        CommonRestrictions.PROCESS_DEFINITION_KEY to "process-1"
      ),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("user-task-1")
      whenever(it.bpmnProcessId).thenReturn("process-2")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isFalse()
    }
  }

  @Test
  fun `should match when taskDescriptionKey is null`() {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = null,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    val job = mock<ActivatedJob> {
      whenever(it.elementId).thenReturn("any-user-task")
    }

    with(delivery) {
      assertThat(subscription.matches(job)).isTrue()
    }
  }

  @Test
  fun `should successfully subscribe and unsubscribe when taskDescriptionKey is null`() {
    val subscriptionRepository = InMemSubscriptionRepository()
    val camundaClient = mock<CamundaClient>()
    val workerBuilder = mock<JobWorkerBuilderStep1>()
    val jobWorker = mock<JobWorker>()

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = null,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { _ -> }
    )

    subscriptionRepository.createTaskSubscription(subscription)

    whenever(camundaClient.newWorker()).thenReturn(workerBuilder)
    val workerBuilder2 = mock<JobWorkerBuilderStep2>()
    whenever(workerBuilder.jobType(any())).thenReturn(workerBuilder2)
    val workerBuilder3 = mock<JobWorkerBuilderStep3>()
    whenever(workerBuilder2.handler(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.maxJobsActive(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.name(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.timeout(any<Long>())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.streamEnabled(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.fetchVariables(any<List<String>>())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.open()).thenReturn(jobWorker)

    val delivery = SubscribingRefreshingZeebeJobUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      workerId = "test-worker",
      userTaskLockTimeoutMs = 60000L
    )

    delivery.subscribe()

    delivery.unsubscribe(subscription)
    verify(jobWorker).close()
  }

  @Test
  fun `should terminate locally cached user task when subscribed delivery fails`() {
    val subscriptionRepository = InMemSubscriptionRepository()
    val userTaskSupport = UserTaskSupport()
    userTaskSupport.addHandler(TaskHandler { _, _ -> error("boom") })

    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = ELEMENT_ID,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = userTaskSupport::onTaskDelivery,
      termination = userTaskSupport::onTaskRemoval
    )
    subscriptionRepository.createTaskSubscription(subscription)

    val camundaClient = mock<CamundaClient>()
    val workerBuilder = mock<JobWorkerBuilderStep1>()
    val workerBuilder2 = mock<JobWorkerBuilderStep2>()
    val workerBuilder3 = mock<JobWorkerBuilderStep3>()
    val jobWorker = mock<JobWorker>()
    val handler = argumentCaptor<JobHandler>()

    whenever(camundaClient.newWorker()).thenReturn(workerBuilder)
    whenever(workerBuilder.jobType(any())).thenReturn(workerBuilder2)
    whenever(workerBuilder2.handler(handler.capture())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.maxJobsActive(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.name(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.timeout(any<Long>())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.streamEnabled(any())).thenReturn(workerBuilder3)
    whenever(workerBuilder3.open()).thenReturn(jobWorker)

    val failJobStep1 = mock<FailJobCommandStep1>()
    val failJobStep2 = mock<FailJobCommandStep1.FailJobCommandStep2>()
    val failJobFuture = mock<CamundaFuture<FailJobResponse>>()
    whenever(camundaClient.newFailCommand(JOB_KEY)).thenReturn(failJobStep1)
    whenever(failJobStep1.retries(RETRIES)).thenReturn(failJobStep2)
    whenever(failJobStep2.send()).thenReturn(failJobFuture)

    val delivery = SubscribingRefreshingZeebeJobUserTaskDelivery(
      camundaClient = camundaClient,
      subscriptionRepository = subscriptionRepository,
      workerId = "test-worker",
      userTaskLockTimeoutMs = 60000L
    )

    delivery.subscribe()

    handler.firstValue.handle(mock<JobClient>(), activatedJob())

    assertThat(userTaskSupport.exists(TASK_ID)).isFalse()
    assertThat(subscriptionRepository.getActiveSubscriptionForTask(TASK_ID)).isNull()
  }

  @Test
  fun `should reject a late activation after public unsubscribe`() {
    assertLateActivationIsReturned { fixture ->
      C8TaskSubscriptionApiImpl(fixture.repository, fixture.delivery)
        .unsubscribe(UnsubscribeFromTaskCmd(fixture.subscription)).join()
      assertThat(fixture.repository.getTaskSubscriptions()).isEmpty()
    }
  }

  @Test
  fun `should reject a late activation after direct unsubscribe`() {
    assertLateActivationIsReturned { it.delivery.unsubscribe(it.subscription) }
  }

  @Test
  fun `should reject a late activation after unsubscribing all workers`() {
    assertLateActivationIsReturned { it.delivery.unsubscribeAll() }
  }

  @Test
  fun `should cancel the entire pending batch when unsubscribing all during the first opening`() {
    val fixture = WorkerFixture()
    fixture.newSubscription()
    fixture.onOpen = { fixture.delivery.unsubscribeAll() }

    fixture.delivery.subscribe()

    verify(fixture.client).newWorker()
    assertThat(fixture.handlers.allValues).hasSize(1)
    assertLateActivationIsReturned(fixture)
  }

  @Test
  fun `should not open a pending subscription removed through the public API`() {
    assertPendingSubscriptionIsNotOpened { fixture, subscription ->
      C8TaskSubscriptionApiImpl(fixture.repository, fixture.delivery)
        .unsubscribe(UnsubscribeFromTaskCmd(subscription)).join()
      assertThat(fixture.repository.getTaskSubscriptions()).doesNotContain(subscription)
    }
  }

  @Test
  fun `should not open a pending subscription after direct unsubscribe`() {
    assertPendingSubscriptionIsNotOpened { fixture, subscription -> fixture.delivery.unsubscribe(subscription) }
  }

  @Test
  fun `should allow cancellation from another thread while opening without publishing later workers`() {
    val fixture = WorkerFixture()
    fixture.newSubscription()
    fixture.onOpen = {
      CompletableFuture.runAsync { fixture.delivery.unsubscribeAll() }.get(5, TimeUnit.SECONDS)
    }

    fixture.delivery.subscribe()

    verify(fixture.client).newWorker()
    assertLateActivationIsReturned(fixture)
  }

  @Test
  fun `should disable and close every worker even when unsubscribe all encounters closing failures`() {
    val fixture = WorkerFixture()
    fixture.newSubscription()
    fixture.newSubscription()
    fixture.delivery.subscribe()
    val firstFailure = IllegalStateException("first worker close failed")
    val secondFailure = IllegalStateException("second worker close failed")
    doThrow(firstFailure).whenever(fixture.workers[0]).close()
    doThrow(secondFailure).whenever(fixture.workers[1]).close()

    assertThatThrownBy { fixture.delivery.unsubscribeAll() }
      .isSameAs(firstFailure)
      .hasSuppressedException(secondFailure)
    fixture.handlers.allValues.forEach { it.handle(mock(), activatedJob()) }

    fixture.workers.forEach { verify(it).close() }
    assertThat(fixture.deliveries).isEmpty()
    assertThat(fixture.terminations).isEmpty()
    assertThat(fixture.repository.getDeliveredTaskIds(TaskType.USER)).isEmpty()
    verify(fixture.failStep1, times(3)).retries(RETRIES)
    fixture.delivery.unsubscribeAll()
    fixture.workers.forEach { verify(it).close() }
  }

  @Test
  fun `should clean up an entire failed batch while preserving opening and closing failures`() {
    val fixture = WorkerFixture()
    fixture.newSubscription()
    fixture.newSubscription()
    val openingFailure = IllegalStateException("second opening failed")
    val closingFailure = IllegalStateException("first worker cleanup failed")
    var openings = 0
    fixture.onOpen = {
      if (++openings == 2) {
        doThrow(closingFailure).whenever(fixture.workers.single()).close()
        throw openingFailure
      }
    }

    assertThatThrownBy { fixture.delivery.subscribe() }
      .isSameAs(openingFailure)
      .hasSuppressedException(closingFailure)
    fixture.handlers.firstValue.handle(mock(), activatedJob())
    fixture.handlers.secondValue.handle(mock(), activatedJob())

    assertThat(fixture.deliveries).isEmpty()
    assertThat(fixture.terminations).isEmpty()
    assertThat(fixture.repository.getDeliveredTaskIds(TaskType.USER)).isEmpty()
    verify(fixture.workers.single()).close()
    verify(fixture.failStep1, times(2)).retries(RETRIES)

    fixture.onOpen = {}
    fixture.delivery.subscribe()
    assertThat(fixture.workers).hasSize(4)
    fixture.handlers.lastValue.handle(mock(), activatedJob())
    assertThat(fixture.deliveries).hasSize(1)
    fixture.delivery.unsubscribeAll()
    fixture.workers.forEach { verify(it).close() }
  }

  private fun assertPendingSubscriptionIsNotOpened(
    unsubscribe: (WorkerFixture, TaskSubscriptionHandle) -> Unit
  ) {
    val fixture = WorkerFixture()
    val pendingSubscription = fixture.newSubscription()
    fixture.onOpen = { unsubscribe(fixture, pendingSubscription) }

    fixture.delivery.subscribe()
    fixture.handlers.firstValue.handle(mock(), activatedJob())

    verify(fixture.client).newWorker()
    assertThat(fixture.handlers.allValues).hasSize(1)
    assertThat(fixture.deliveries).hasSize(1)
    assertThat(fixture.terminations).isEmpty()
    assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isSameAs(fixture.subscription)
    verify(fixture.workers.single(), never()).close()
  }

  @Test
  fun `should cancel a worker when public unsubscribe occurs during opening`() {
    val fixture = WorkerFixture()
    fixture.onOpen = {
      C8TaskSubscriptionApiImpl(fixture.repository, fixture.delivery)
        .unsubscribe(UnsubscribeFromTaskCmd(fixture.subscription)).join()
    }

    fixture.delivery.subscribe()

    assertLateActivationIsReturned(fixture)
    assertThat(fixture.repository.getTaskSubscriptions()).isEmpty()
  }

  @Test
  fun `should cancel a worker when direct unsubscribe occurs during opening`() {
    val fixture = WorkerFixture()
    fixture.onOpen = { fixture.delivery.unsubscribe(fixture.subscription) }

    fixture.delivery.subscribe()

    assertLateActivationIsReturned(fixture)
  }

  @Test
  fun `should cancel a worker when unsubscribe all occurs during opening`() {
    val fixture = WorkerFixture()
    fixture.onOpen = { fixture.delivery.unsubscribeAll() }

    fixture.delivery.subscribe()

    assertLateActivationIsReturned(fixture)
  }

  @Test
  fun `should allow a callback during opening to unsubscribe without permitting later callbacks`() {
    val fixture = WorkerFixture(deliverDuringOpen = true)
    fixture.onDelivery = { fixture.delivery.unsubscribe(fixture.subscription) }

    fixture.delivery.subscribe()
    fixture.handlers.firstValue.handle(mock(), activatedJob())

    assertThat(fixture.deliveries).hasSize(1)
    assertThat(fixture.terminations).isEmpty()
    verify(fixture.workers.single()).close()
    verify(fixture.failStep1).retries(RETRIES)
  }

  @Test
  fun `should disable a failed opening callback and allow a fresh registration`() {
    val fixture = WorkerFixture()
    val failure = IllegalStateException("opening failed")
    fixture.onOpen = { throw failure }

    assertThatThrownBy { fixture.delivery.subscribe() }.isSameAs(failure)
    fixture.handlers.firstValue.handle(mock(), activatedJob())
    assertThat(fixture.deliveries).isEmpty()
    assertThat(fixture.terminations).isEmpty()
    assertThat(fixture.repository.getDeliveredTaskIds(TaskType.USER)).isEmpty()

    fixture.onOpen = {}
    fixture.delivery.subscribe()
    fixture.handlers.secondValue.handle(mock(), activatedJob())

    assertThat(fixture.deliveries).hasSize(1)
    assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isSameAs(fixture.subscription)
    fixture.delivery.unsubscribe(fixture.subscription)
    verify(fixture.workers.single()).close()
    verify(fixture.failStep1).retries(RETRIES)
  }

  @Test
  fun `should not remove a newer registration when an older opening fails`() {
    val fixture = WorkerFixture()
    val failure = IllegalStateException("older opening failed")
    fixture.onOpen = {
      fixture.onOpen = {}
      fixture.delivery.subscribe()
      throw failure
    }

    assertThatThrownBy { fixture.delivery.subscribe() }.isSameAs(failure)
    fixture.handlers.firstValue.handle(mock(), activatedJob())
    fixture.handlers.secondValue.handle(mock(), activatedJob())
    fixture.delivery.unsubscribe(fixture.subscription)

    assertThat(fixture.deliveries).hasSize(1)
    verify(fixture.workers.single()).close()
    verify(fixture.failStep1).retries(RETRIES)
  }

  @Test
  fun `should keep acceptance disabled when closing a worker fails`() {
    val fixture = WorkerFixture()
    fixture.delivery.subscribe()
    val failure = IllegalStateException("closing failed")
    doThrow(failure).whenever(fixture.workers.single()).close()

    assertThatThrownBy { fixture.delivery.unsubscribe(fixture.subscription) }.isSameAs(failure)

    assertLateActivationIsReturned(fixture)
  }

  @Test
  fun `should deliver an active subscription even when activation arrives during worker opening`() {
    val fixture = WorkerFixture(deliverDuringOpen = true)

    fixture.delivery.subscribe()

    assertThat(fixture.deliveries).hasSize(1)
    assertThat(fixture.deliveries.single().meta).containsEntry(TaskInformation.REASON, TaskInformation.CREATE)
    assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isSameAs(fixture.subscription)
    verify(fixture.client, never()).newFailCommand(any<Long>())
  }

  @Test
  fun `should reject only the unsubscribed worker while another subscription remains active`() {
    val fixture = WorkerFixture()
    val otherSubscription = fixture.newSubscription()
    fixture.delivery.subscribe()

    fixture.delivery.unsubscribe(fixture.subscription)
    fixture.handlers.firstValue.handle(mock(), activatedJob())
    fixture.handlers.secondValue.handle(mock(), activatedJob())

    assertThat(fixture.deliveries).hasSize(1)
    assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isSameAs(otherSubscription)
    assertThat(fixture.terminations).isEmpty()
    verify(fixture.workers.first()).close()
    verify(fixture.workers.last(), never()).close()
    verify(fixture.failStep1).retries(RETRIES)
  }

  @Test
  fun `should keep an old worker disabled when the same subscription is opened again`() {
    val fixture = WorkerFixture()
    fixture.delivery.subscribe()
    fixture.delivery.unsubscribe(fixture.subscription)
    fixture.delivery.subscribe()

    fixture.handlers.firstValue.handle(mock(), activatedJob())
    fixture.handlers.secondValue.handle(mock(), activatedJob())

    assertThat(fixture.deliveries).hasSize(1)
    assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isSameAs(fixture.subscription)
    verify(fixture.failStep1).retries(RETRIES)
  }

  private fun assertLateActivationIsReturned(unsubscribe: (WorkerFixture) -> Unit) {
    val fixture = WorkerFixture()
    fixture.delivery.subscribe()
    unsubscribe(fixture)

    assertLateActivationIsReturned(fixture)
  }

  private fun assertLateActivationIsReturned(fixture: WorkerFixture) {
    fixture.handlers.firstValue.handle(mock(), activatedJob())

    assertThat(fixture.deliveries).isEmpty()
    assertThat(fixture.terminations).isEmpty()
    assertThat(fixture.repository.getActiveSubscriptionForTask(TASK_ID)).isNull()
    assertThat(fixture.repository.getDeliveredTaskIds(TaskType.USER)).isEmpty()
    verify(fixture.workers.single()).close()
    verify(fixture.failStep1).retries(RETRIES)
    verifyNoMoreInteractions(fixture.failStep1)
    verify(fixture.failStep2).send()
    verify(fixture.failFuture).join()
    verify(fixture.client, never()).newCompleteCommand(any<Long>())
  }

  private inner class WorkerFixture(deliverDuringOpen: Boolean = false) {
    val repository = InMemSubscriptionRepository()
    val client = mock<CamundaClient>()
    val deliveries = mutableListOf<TaskInformation>()
    val terminations = mutableListOf<TaskInformation>()
    val subscription = newSubscription()
    val delivery = SubscribingRefreshingZeebeJobUserTaskDelivery(client, repository, "test-worker", 60000L)
    val handlers = argumentCaptor<JobHandler>()
    val workers = mutableListOf<JobWorker>()
    val failStep1 = mock<FailJobCommandStep1>()
    val failStep2 = mock<FailJobCommandStep1.FailJobCommandStep2>()
    val failFuture = mock<CamundaFuture<FailJobResponse>>()
    var onOpen: () -> Unit = {}
    var onDelivery: () -> Unit = {}

    init {
      val builder = mock<JobWorkerBuilderStep1>()
      val builder2 = mock<JobWorkerBuilderStep2>()
      val builder3 = mock<JobWorkerBuilderStep3>()
      whenever(client.newWorker()).thenReturn(builder)
      whenever(builder.jobType(any())).thenReturn(builder2)
      whenever(builder2.handler(handlers.capture())).thenReturn(builder3)
      whenever(builder3.maxJobsActive(any())).thenReturn(builder3)
      whenever(builder3.name(any())).thenReturn(builder3)
      whenever(builder3.timeout(any<Long>())).thenReturn(builder3)
      whenever(builder3.streamEnabled(any())).thenReturn(builder3)
      whenever(builder3.open()).thenAnswer {
        onOpen()
        if (deliverDuringOpen) handlers.lastValue.handle(mock(), activatedJob())
        mock<JobWorker>().also { workers.add(it) }
      }
      whenever(client.newFailCommand(JOB_KEY)).thenReturn(failStep1)
      whenever(failStep1.retries(RETRIES)).thenReturn(failStep2)
      whenever(failStep2.send()).thenReturn(failFuture)
    }

    fun newSubscription(): TaskSubscriptionHandle = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = ELEMENT_ID,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { task, _ ->
        deliveries.add(task)
        onDelivery()
      },
      termination = { terminations.add(it) }
    ).also { repository.createTaskSubscription(it) }
  }

  @Test
  fun `should terminate a user task once when REST refresh reports not found`() {
    assertGoneTaskIsTerminated(ProblemException(404, "Not Found", ProblemDetail().setStatus(404)))
  }

  @Test
  fun `should terminate a user task once when gRPC refresh reports not found`() {
    assertGoneTaskIsTerminated(ClientStatusException(Status.NOT_FOUND, null))
  }

  @Test
  fun `should retain a user task and continue refreshing others on other REST errors`() {
    assertFailedTaskIsRetainedAndOthersRefreshed(ProblemException(500, "Internal Server Error", ProblemDetail().setStatus(500)))
  }

  @Test
  fun `should retain a user task and continue refreshing others on other gRPC errors`() {
    assertFailedTaskIsRetainedAndOthersRefreshed(ClientStatusException(Status.UNAVAILABLE, null))
  }

  private fun assertGoneTaskIsTerminated(error: RuntimeException) {
    val repository = InMemSubscriptionRepository()
    val terminations = mutableListOf<TaskInformation>()
    val subscription = deliveredSubscription(repository, terminations, TASK_ID)
    val client = mock<CamundaClient>()
    val future = timeoutFuture(client, JOB_KEY)
    whenever(future.join()).thenThrow(error)
    val delivery = SubscribingRefreshingZeebeJobUserTaskDelivery(client, repository, "test-worker", 60000L)

    delivery.refresh()
    delivery.refresh()

    assertThat(terminations).hasSize(1)
    assertThat(terminations.single().taskId).isEqualTo(TASK_ID)
    assertThat(terminations.single().meta).containsEntry(TaskInformation.REASON, TaskInformation.DELETE)
    assertThat(repository.getActiveSubscriptionForTask(TASK_ID)).isNull()
    assertThat(repository.getDeliveredTaskIds(TaskType.USER)).isEmpty()
    assertThat(repository.getTaskSubscriptions()).contains(subscription)
    verify(client).newUpdateTimeoutCommand(JOB_KEY)
    verify(future).join()
  }

  private fun assertFailedTaskIsRetainedAndOthersRefreshed(error: RuntimeException) {
    val repository = InMemSubscriptionRepository()
    val terminations = mutableListOf<TaskInformation>()
    val otherTaskId = "9877"
    val subscription = deliveredSubscription(repository, terminations, TASK_ID, otherTaskId)
    val client = mock<CamundaClient>()
    val failingFuture = timeoutFuture(client, JOB_KEY)
    whenever(failingFuture.join()).thenThrow(error)
    val otherFuture = timeoutFuture(client, otherTaskId.toLong())
    val delivery = SubscribingRefreshingZeebeJobUserTaskDelivery(client, repository, "test-worker", 60000L)

    delivery.refresh()
    delivery.refresh()

    assertThat(terminations).isEmpty()
    assertThat(repository.getActiveSubscriptionForTask(TASK_ID)).isEqualTo(subscription)
    assertThat(repository.getDeliveredTaskIds(TaskType.USER)).containsExactlyInAnyOrder(TASK_ID, otherTaskId)
    verify(failingFuture, times(2)).join()
    verify(otherFuture, times(2)).join()
  }

  private fun deliveredSubscription(
    repository: InMemSubscriptionRepository,
    terminations: MutableList<TaskInformation>,
    vararg taskIds: String
  ): TaskSubscriptionHandle {
    val subscription = TaskSubscriptionHandle(
      taskType = TaskType.USER,
      taskDescriptionKey = ELEMENT_ID,
      restrictions = emptyMap(),
      payloadDescription = null,
      action = { _, _ -> },
      termination = { terminations.add(it) }
    )
    repository.createTaskSubscription(subscription)
    taskIds.forEach { repository.activateSubscriptionForTask(it, subscription) }
    return subscription
  }

  private fun timeoutFuture(client: CamundaClient, jobKey: Long): CamundaFuture<UpdateTimeoutJobResponse> {
    val step1 = mock<UpdateTimeoutJobCommandStep1>()
    val step2 = mock<UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2>()
    val future = mock<CamundaFuture<UpdateTimeoutJobResponse>>()
    whenever(client.newUpdateTimeoutCommand(jobKey)).thenReturn(step1)
    whenever(step1.timeout(60000L)).thenReturn(step2)
    whenever(step2.send()).thenReturn(future)
    return future
  }

  private fun activatedJob(): ActivatedJob =
    mock {
      whenever(it.key).thenReturn(JOB_KEY)
      whenever(it.retries).thenReturn(RETRIES)
      whenever(it.type).thenReturn(Protocol.USER_TASK_JOB_TYPE)
      whenever(it.elementId).thenReturn(ELEMENT_ID)
      whenever(it.tenantId).thenReturn(TENANT_ID)
      whenever(it.bpmnProcessId).thenReturn(BPMN_PROCESS_ID)
      whenever(it.processDefinitionKey).thenReturn(PROCESS_DEFINITION_KEY)
      whenever(it.processInstanceKey).thenReturn(PROCESS_INSTANCE_KEY)
      whenever(it.variablesAsMap).thenReturn(emptyMap())
      whenever(it.customHeaders).thenReturn(emptyMap())
    }
}
