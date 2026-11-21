package dev.bpmcrafters.processengineapi.adapter.c8.task.modification

import dev.bpmcrafters.processengineapi.task.ChangeAssignmentModifyTaskCmd
import dev.bpmcrafters.processengineapi.task.ChangeDatesModifyTaskCmd
import dev.bpmcrafters.processengineapi.task.CompositeModifyTaskCmd
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.UpdateUserTaskCommandStep1
import io.camunda.client.api.response.UpdateUserTaskResponse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

class C8CamundaClientUserTaskModificationApiImplTest {

  private val camundaClient: CamundaClient = mock()
  private val modificationApi = C8CamundaClientUserTaskModificationApiImpl(camundaClient)

  @Test
  fun `set candidate users updates the user task`() {
    val candidateUsers = listOf("user1", "user2")
    update { command ->
      whenever(command.candidateUsers(candidateUsers)).thenReturn(command)
      modificationApi.update(ChangeAssignmentModifyTaskCmd.SetCandidateUsersTaskCmd(TASK_ID, candidateUsers))
      verify(command).candidateUsers(candidateUsers)
    }
  }

  @Test
  fun `clear candidate users updates the user task`() = update { command ->
    whenever(command.clearCandidateUsers()).thenReturn(command)
    modificationApi.update(ChangeAssignmentModifyTaskCmd.ClearCandidateUsersTaskCmd(TASK_ID))
    verify(command).clearCandidateUsers()
  }

  @Test
  fun `set candidate groups updates the user task`() {
    val candidateGroups = listOf("group1", "group2")
    update { command ->
      whenever(command.candidateGroups(candidateGroups)).thenReturn(command)
      modificationApi.update(ChangeAssignmentModifyTaskCmd.SetCandidateGroupsTaskCmd(TASK_ID, candidateGroups))
      verify(command).candidateGroups(candidateGroups)
    }
  }

  @Test
  fun `clear candidate groups updates the user task`() = update { command ->
    whenever(command.clearCandidateGroups()).thenReturn(command)
    modificationApi.update(ChangeAssignmentModifyTaskCmd.ClearCandidateGroupsTaskCmd(TASK_ID))
    verify(command).clearCandidateGroups()
  }

  @Test
  fun `set due date updates the user task`() {
    val dueDate = OffsetDateTime.parse("2026-04-16T12:34:56+02:00")
    update { command ->
      whenever(command.dueDate(dueDate)).thenReturn(command)
      modificationApi.update(ChangeDatesModifyTaskCmd.SetDueDateTaskCmd(TASK_ID, dueDate))
      verify(command).dueDate(dueDate)
    }
  }

  @Test
  fun `clear due date updates the user task`() = update { command ->
    whenever(command.clearDueDate()).thenReturn(command)
    modificationApi.update(ChangeDatesModifyTaskCmd.ClearDueDateTaskCmd(TASK_ID))
    verify(command).clearDueDate()
  }

  @Test
  fun `set follow up date updates the user task`() {
    val followUpDate = OffsetDateTime.parse("2026-04-17T12:34:56+02:00")
    update { command ->
      whenever(command.followUpDate(followUpDate)).thenReturn(command)
      modificationApi.update(ChangeDatesModifyTaskCmd.SetFollowUpDateTaskCmd(TASK_ID, followUpDate))
      verify(command).followUpDate(followUpDate)
    }
  }

  @Test
  fun `clear follow up date updates the user task`() = update { command ->
    whenever(command.clearFollowUpDate()).thenReturn(command)
    modificationApi.update(ChangeDatesModifyTaskCmd.ClearFollowUpDateTaskCmd(TASK_ID))
    verify(command).clearFollowUpDate()
  }

  @Test
  fun `composite command executes nested date commands`() {
    val dueDate = OffsetDateTime.parse("2026-04-16T12:34:56+02:00")
    val followUpDate = OffsetDateTime.parse("2026-04-17T12:34:56+02:00")
    val firstCommand = command()
    val secondCommand = command()
    whenever(camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong())).thenReturn(firstCommand, secondCommand)
    whenever(firstCommand.dueDate(dueDate)).thenReturn(firstCommand)
    whenever(secondCommand.followUpDate(followUpDate)).thenReturn(secondCommand)

    modificationApi.update(
      CompositeModifyTaskCmd(
        TASK_ID,
        listOf(
          ChangeDatesModifyTaskCmd.SetDueDateTaskCmd(TASK_ID, dueDate),
          ChangeDatesModifyTaskCmd.SetFollowUpDateTaskCmd(TASK_ID, followUpDate)
        )
      )
    )

    verify(firstCommand).dueDate(dueDate)
    verify(firstCommand).send()
    verify(secondCommand).followUpDate(followUpDate)
    verify(secondCommand).send()
  }

  @Test
  fun `unsupported commands are not supported`() {
    assertThrows(UnsupportedOperationException::class.java) {
      modificationApi.update(ChangeAssignmentModifyTaskCmd.AddCandidateUserTaskCmd(TASK_ID, "demo"))
    }

    verify(camundaClient, never()).newUpdateUserTaskCommand(any())
  }

  private fun update(assertions: (UpdateUserTaskCommandStep1) -> Unit) {
    val command = command()
    whenever(camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong())).thenReturn(command)
    assertions(command)
    verify(command).send()
  }

  private fun command(): UpdateUserTaskCommandStep1 {
    val command: UpdateUserTaskCommandStep1 = mock()
    val future: CamundaFuture<UpdateUserTaskResponse> = mock()
    whenever(command.send()).thenReturn(future)
    return command
  }

  private companion object {
    const val TASK_ID = "1234"
  }
}
