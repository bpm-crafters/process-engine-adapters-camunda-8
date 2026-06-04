package dev.bpmcrafters.processengineapi.adapter.c8.task.modification

import dev.bpmcrafters.processengineapi.Empty
import dev.bpmcrafters.processengineapi.task.*
import io.camunda.client.CamundaClient
import io.camunda.client.api.command.UpdateUserTaskCommandStep1
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger {}

/**
 * Implementation of C8 modification API.
 * @since 2025-06-01
 */
class C8CamundaClientUserTaskModificationApiImpl(
  private val camundaClient: CamundaClient
) : UserTaskModificationApi {

  override fun update(cmd: ModifyTaskCmd): CompletableFuture<Empty> {
    logger.debug { "PROCESS-ENGINE-C8-015: modifying user task ${cmd.taskId}." }
    apply(cmd)
    logger.debug { "PROCESS-ENGINE-C8-016: successfully modified user task ${cmd.taskId}." }
    return CompletableFuture.completedFuture(Empty)
  }

  private fun apply(cmd: ModifyTaskCmd) {
    when (cmd) {
      is CompositeModifyTaskCmd -> cmd.commands.forEach(::apply)

      is ChangeAssignmentModifyTaskCmd.SetCandidateUsersTaskCmd -> sendUpdate(cmd.taskId) { it.candidateUsers(cmd.candidateUsers) }
      is ChangeAssignmentModifyTaskCmd.ClearCandidateUsersTaskCmd -> sendUpdate(cmd.taskId) { it.clearCandidateUsers() }
      is ChangeAssignmentModifyTaskCmd.SetCandidateGroupsTaskCmd -> sendUpdate(cmd.taskId) { it.candidateGroups(cmd.candidateGroups) }
      is ChangeAssignmentModifyTaskCmd.ClearCandidateGroupsTaskCmd -> sendUpdate(cmd.taskId) { it.clearCandidateGroups() }

      is ChangeDatesModifyTaskCmd.SetDueDateTaskCmd -> sendUpdate(cmd.taskId) { it.dueDate(cmd.dueDate) }
      is ChangeDatesModifyTaskCmd.ClearDueDateTaskCmd -> sendUpdate(cmd.taskId) { it.clearDueDate() }
      is ChangeDatesModifyTaskCmd.SetFollowUpDateTaskCmd -> sendUpdate(cmd.taskId) { it.followUpDate(cmd.followUpDate) }
      is ChangeDatesModifyTaskCmd.ClearFollowUpDateTaskCmd -> sendUpdate(cmd.taskId) { it.clearFollowUpDate() }

      else -> throw UnsupportedOperationException("Unsupported user task modification command: ${cmd::class.qualifiedName}")
    }
  }

  private fun sendUpdate(taskId: String, updater: (UpdateUserTaskCommandStep1) -> Unit) {
    val command = camundaClient.newUpdateUserTaskCommand(taskId.toLong())
    updater(command)
    command.send().join()
  }
}
