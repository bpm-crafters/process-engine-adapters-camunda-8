package dev.bpmcrafters.processengineapi.adapter.c8.task.modification

import dev.bpmcrafters.processengineapi.task.ChangeAssignmentModifyTaskCmd
import dev.bpmcrafters.processengineapi.task.ChangeDatesModifyTaskCmd
import dev.bpmcrafters.processengineapi.task.CompositeModifyTaskCmd
import io.camunda.client.CamundaClient
import io.camunda.client.api.CamundaFuture
import io.camunda.client.api.command.UpdateUserTaskCommandStep1
import io.camunda.client.api.response.UpdateUserTaskResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class C8CamundaClientUserTaskModificationApiImplTest {

  companion object {
    private const val TASK_ID = "1234"
  }

  private val camundaClient: CamundaClient = mockk()
  private val modificationApi = C8CamundaClientUserTaskModificationApiImpl(camundaClient)

  @Test
  fun `set candidate users updates the user task`() {
    val candidateUsers = listOf("user1", "user2")
    val updateCommand: UpdateUserTaskCommandStep1 = mockk()
    val future: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)

    every { camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong()) } returns updateCommand
    every { updateCommand.candidateUsers(candidateUsers) } returns updateCommand
    every { updateCommand.send() } returns future

    modificationApi.update(ChangeAssignmentModifyTaskCmd.SetCandidateUsersTaskCmd(TASK_ID, candidateUsers))

    verify {
      updateCommand.candidateUsers(candidateUsers)
      updateCommand.send()
    }
  }

  @Test
  fun `clear candidate users updates the user task`() {
    val updateCommand: UpdateUserTaskCommandStep1 = mockk()
    val future: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)

    every { camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong()) } returns updateCommand
    every { updateCommand.clearCandidateUsers() } returns updateCommand
    every { updateCommand.send() } returns future

    modificationApi.update(ChangeAssignmentModifyTaskCmd.ClearCandidateUsersTaskCmd(TASK_ID))

    verify {
      updateCommand.clearCandidateUsers()
      updateCommand.send()
    }
  }

  @Test
  fun `set candidate groups updates the user task`() {
    val candidateGroups = listOf("group1", "group2")
    val updateCommand: UpdateUserTaskCommandStep1 = mockk()
    val future: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)

    every { camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong()) } returns updateCommand
    every { updateCommand.candidateGroups(candidateGroups) } returns updateCommand
    every { updateCommand.send() } returns future

    modificationApi.update(ChangeAssignmentModifyTaskCmd.SetCandidateGroupsTaskCmd(TASK_ID, candidateGroups))

    verify {
      updateCommand.candidateGroups(candidateGroups)
      updateCommand.send()
    }
  }

  @Test
  fun `clear candidate groups updates the user task`() {
    val updateCommand: UpdateUserTaskCommandStep1 = mockk()
    val future: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)

    every { camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong()) } returns updateCommand
    every { updateCommand.clearCandidateGroups() } returns updateCommand
    every { updateCommand.send() } returns future

    modificationApi.update(ChangeAssignmentModifyTaskCmd.ClearCandidateGroupsTaskCmd(TASK_ID))

    verify {
      updateCommand.clearCandidateGroups()
      updateCommand.send()
    }
  }

  @Test
  fun `set due date updates the user task`() {
    val dueDate = OffsetDateTime.parse("2026-04-16T12:34:56+02:00")
    val updateCommand: UpdateUserTaskCommandStep1 = mockk()
    val future: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)

    every { camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong()) } returns updateCommand
    every { updateCommand.dueDate(dueDate) } returns updateCommand
    every { updateCommand.send() } returns future

    modificationApi.update(ChangeDatesModifyTaskCmd.SetDueDateTaskCmd(TASK_ID, dueDate))

    verify {
      updateCommand.dueDate(dueDate)
      updateCommand.send()
    }
  }

  @Test
  fun `clear due date updates the user task`() {
    val updateCommand: UpdateUserTaskCommandStep1 = mockk()
    val future: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)

    every { camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong()) } returns updateCommand
    every { updateCommand.clearDueDate() } returns updateCommand
    every { updateCommand.send() } returns future

    modificationApi.update(ChangeDatesModifyTaskCmd.ClearDueDateTaskCmd(TASK_ID))

    verify {
      updateCommand.clearDueDate()
      updateCommand.send()
    }
  }

  @Test
  fun `set follow up date updates the user task`() {
    val followUpDate = OffsetDateTime.parse("2026-04-17T12:34:56+02:00")
    val updateCommand: UpdateUserTaskCommandStep1 = mockk()
    val future: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)

    every { camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong()) } returns updateCommand
    every { updateCommand.followUpDate(followUpDate) } returns updateCommand
    every { updateCommand.send() } returns future

    modificationApi.update(ChangeDatesModifyTaskCmd.SetFollowUpDateTaskCmd(TASK_ID, followUpDate))

    verify {
      updateCommand.followUpDate(followUpDate)
      updateCommand.send()
    }
  }

  @Test
  fun `clear follow up date updates the user task`() {
    val updateCommand: UpdateUserTaskCommandStep1 = mockk()
    val future: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)

    every { camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong()) } returns updateCommand
    every { updateCommand.clearFollowUpDate() } returns updateCommand
    every { updateCommand.send() } returns future

    modificationApi.update(ChangeDatesModifyTaskCmd.ClearFollowUpDateTaskCmd(TASK_ID))

    verify {
      updateCommand.clearFollowUpDate()
      updateCommand.send()
    }
  }

  @Test
  fun `composite command executes nested date commands`() {
    val dueDate = OffsetDateTime.parse("2026-04-16T12:34:56+02:00")
    val followUpDate = OffsetDateTime.parse("2026-04-17T12:34:56+02:00")
    val firstUpdateCommand: UpdateUserTaskCommandStep1 = mockk()
    val secondUpdateCommand: UpdateUserTaskCommandStep1 = mockk()
    val firstFuture: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)
    val secondFuture: CamundaFuture<UpdateUserTaskResponse> = mockk(relaxed = true)

    every {
      camundaClient.newUpdateUserTaskCommand(TASK_ID.toLong())
    } returnsMany listOf(firstUpdateCommand, secondUpdateCommand)
    every { firstUpdateCommand.dueDate(dueDate) } returns firstUpdateCommand
    every { firstUpdateCommand.send() } returns firstFuture
    every { secondUpdateCommand.followUpDate(followUpDate) } returns secondUpdateCommand
    every { secondUpdateCommand.send() } returns secondFuture

    modificationApi.update(
      CompositeModifyTaskCmd(
        TASK_ID,
        listOf(
          ChangeDatesModifyTaskCmd.SetDueDateTaskCmd(TASK_ID, dueDate),
          ChangeDatesModifyTaskCmd.SetFollowUpDateTaskCmd(TASK_ID, followUpDate)
        )
      )
    )

    verify {
      firstUpdateCommand.dueDate(dueDate)
      firstUpdateCommand.send()
      secondUpdateCommand.followUpDate(followUpDate)
      secondUpdateCommand.send()
    }
  }

  @Test
  fun `unsupported commands are not supported`() {
    assertThrows(UnsupportedOperationException::class.java) {
      modificationApi.update(ChangeAssignmentModifyTaskCmd.AssignTaskCmd(TASK_ID, "demo"))
    }
  }
}
