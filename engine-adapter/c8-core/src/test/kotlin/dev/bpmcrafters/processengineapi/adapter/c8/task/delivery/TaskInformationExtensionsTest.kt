package dev.bpmcrafters.processengineapi.adapter.c8.task.delivery

import io.camunda.client.api.response.ActivatedJob
import io.camunda.client.api.search.enums.UserTaskState
import io.camunda.client.api.search.response.Form
import io.camunda.client.api.search.response.UserTask
import io.camunda.zeebe.protocol.Protocol
import org.assertj.core.api.Assertions.assertThat
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

}
