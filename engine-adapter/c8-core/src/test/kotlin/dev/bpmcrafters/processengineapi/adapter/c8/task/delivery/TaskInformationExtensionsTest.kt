package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.response.UserTaskProperties
import io.camunda.client.api.search.enums.JobKind
import io.camunda.client.api.search.enums.ListenerEventType
import io.camunda.client.api.search.enums.UserTaskState
import io.camunda.client.api.search.response.Form
import io.camunda.client.api.search.response.UserTask
import io.camunda.zeebe.protocol.Protocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date

class TaskInformationExtensionsTest {

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = ["Sat, 12 Aug 1995 13:30:00 GMT+0430"])
  fun `should map ActivatedJob`(maybeNullDate: Date?) {
    val activatedJob = mock<ActivatedJob> {
      val customHeaders = maybeNullDate?.let { mapOf(Pair(Protocol.USER_TASK_DUE_DATE_HEADER_NAME, it.toString())) } ?: mapOf()
      whenever { it.customHeaders }.thenReturn(customHeaders)
    }
    val taskInformation = activatedJob.toTaskInformation()
    assertThat(taskInformation).isNotNull
    if (maybeNullDate == null) {
      assertThat(taskInformation.meta).doesNotContainKey("dueDate")
    } else {
      assertThat(taskInformation.meta).containsEntry("dueDate", maybeNullDate.toString())
    }

  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = ["Sat, 12 Aug 1995 13:30:00 GMT+0430"])
  fun `should map UserTask without form`(maybeNullDate: Date?) {
    val dueDate = maybeNullDate?.toInstant()?.atOffset(ZoneOffset.UTC)

    val userTask = mock<UserTask> {
      whenever { it.creationDate }.thenReturn(OffsetDateTime.now())
      whenever { it.dueDate }.thenReturn(dueDate)
      whenever { it.state }.thenReturn(UserTaskState.CREATED)
    }
    val taskInformation = userTask.toTaskInformation(null)
    assertThat(taskInformation).isNotNull
    assertThat(taskInformation.meta).containsKey("creationDate")
    assertThat(taskInformation.meta).containsEntry("taskState", "CREATED")
    assertThat(taskInformation.meta).doesNotContainKey("formKey")
    if (maybeNullDate == null) {
      assertThat(taskInformation.meta).doesNotContainKey("dueDate")
    } else {
      assertThat(taskInformation.meta).containsEntry("dueDate", dueDate.toString())
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = ["Sat, 12 Aug 1995 13:30:00 GMT+0430"])
  fun `should map UserTask with form`(maybeNullDate: Date?) {
    val dueDate = maybeNullDate?.toInstant()?.atOffset(ZoneOffset.UTC)
    val userTask = mock<UserTask> {
      whenever { it.creationDate }.thenReturn(OffsetDateTime.now())
      whenever { it.dueDate }.thenReturn(dueDate)
      whenever { it.state }.thenReturn(UserTaskState.CREATED)
    }
    val form = mock<Form> {
      whenever { it.formId }.thenReturn("form1")
    }
    val taskInformation = userTask.toTaskInformation(form)
    assertThat(taskInformation).isNotNull
    assertThat(taskInformation.meta).containsKey("creationDate")
    assertThat(taskInformation.meta).containsEntry("taskState", "CREATED")
    assertThat(taskInformation.meta).containsEntry("formKey", "form1")
    if (maybeNullDate == null) {
      assertThat(taskInformation.meta).doesNotContainKey("dueDate")
    } else {
      assertThat(taskInformation.meta).containsEntry("dueDate", dueDate.toString())
    }
  }

  @Test
  fun `should map user task listener ActivatedJob`() {
    val userTask = mock<UserTaskProperties> {
      whenever { it.userTaskKey }.thenReturn(1234L)
      whenever { it.action }.thenReturn("complete")
      whenever { it.assignee }.thenReturn("kermit")
      whenever { it.candidateUsers }.thenReturn(listOf("gonzo", "fozzy"))
      whenever { it.candidateGroups }.thenReturn(listOf("avengers"))
      whenever { it.changedAttributes }.thenReturn(listOf("assignee", "priority"))
      whenever { it.priority }.thenReturn(80)
    }
    val activatedJob = mock<ActivatedJob> {
      whenever { it.kind }.thenReturn(JobKind.TASK_LISTENER)
      whenever { it.key }.thenReturn(9876L)
      whenever { it.type }.thenReturn("process-engine-user-tasks")
      whenever { it.listenerEventType }.thenReturn(ListenerEventType.COMPLETING)
      whenever { it.userTask }.thenReturn(userTask)
      whenever { it.tenantId }.thenReturn("tenant-1")
      whenever { it.elementId }.thenReturn("user-task")
      whenever { it.elementInstanceKey }.thenReturn(222L)
      whenever { it.bpmnProcessId }.thenReturn("simple-process")
      whenever { it.processDefinitionKey }.thenReturn(333L)
      whenever { it.processInstanceKey }.thenReturn(444L)
      whenever { it.retries }.thenReturn(3)
    }

    val event = activatedJob.toUserTaskListenerEvent()
    val taskInformation = activatedJob.toUserTaskListenerTaskInformation()

    assertThat(event.taskId).isEqualTo("1234")
    assertThat(event.eventType).isEqualTo(ListenerEventType.COMPLETING)
    assertThat(taskInformation.taskId).isEqualTo("1234")
    assertThat(taskInformation.meta).containsEntry("eventType", "COMPLETING")
    assertThat(taskInformation.meta).containsEntry("action", "complete")
    assertThat(taskInformation.meta).containsEntry("assignee", "kermit")
    assertThat(taskInformation.meta).containsEntry("candidateUsers", "gonzo,fozzy")
    assertThat(taskInformation.meta).containsEntry("candidateGroups", "avengers")
    assertThat(taskInformation.meta).containsEntry("changedAttributes", "assignee,priority")
    assertThat(taskInformation.meta).containsEntry("priority", "80")
    assertThat(taskInformation.meta).containsEntry("topicName", "process-engine-user-tasks")
  }

}
