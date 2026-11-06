package dev.bpmcrafters.example.javac8quarkus.adapter.in.rest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.bpmcrafters.example.javac8quarkus.adapter.in.process.TaskSubscriptions;
import dev.bpmcrafters.example.javac8quarkus.adapter.out.process.WorkflowAdapter;
import dev.bpmcrafters.processengineapi.Empty;
import dev.bpmcrafters.processengineapi.task.*;
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport;
import jakarta.ws.rs.BadRequestException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SimpleServiceTaskResourceTest {
  private final WorkflowAdapter workflow = mock(WorkflowAdapter.class);
  private final TaskSubscriptions tasks = mock(TaskSubscriptions.class);
  private final UserTaskCompletionApi completion = mock(UserTaskCompletionApi.class);
  private final SimpleServiceTaskResource resource = new SimpleServiceTaskResource(workflow, tasks, completion);

  @Test
  void returnsCreatedLocationsAndEmptyBodies() {
    when(workflow.deploy()).thenReturn("456");
    when(workflow.start("unique", 123)).thenReturn("789");
    try (final var deploy = resource.deploy(); final var start = resource.start("unique", "123")) {
      assertThat(deploy.getStatus()).isEqualTo(201);
      assertThat(deploy.getLocation().toString()).isEqualTo("456");
      assertThat(deploy.hasEntity()).isFalse();
      assertThat(start.getStatus()).isEqualTo(201);
      assertThat(start.getLocation().toString()).isEqualTo("789");
      assertThat(start.hasEntity()).isFalse();
    }
  }

  @Test
  void validatesRequiredValuesAndIntegerRangeBeforeCallingTheEngine() {
    assertThatThrownBy(() -> resource.start("unique", null)).isInstanceOf(BadRequestException.class);
    for (final var invalid : java.util.List.of("", " ", "bad", "2147483648")) {
      assertThatThrownBy(() -> resource.start("unique", invalid)).isInstanceOf(BadRequestException.class);
    }
    assertThatThrownBy(() -> resource.start(null, "123")).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.correlate("unique", " ")).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.signal(null)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.complete("123", null)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> resource.error("123", null)).isInstanceOf(BadRequestException.class);
    verifyNoInteractions(workflow, completion);
  }

  @Test
  void completesWithTheExpectedPayloadAndPassesMessageAndSignalValues() {
    when(completion.completeTask(any())).thenReturn(CompletableFuture.completedFuture(Empty.INSTANCE));
    try (final var completed = resource.complete("123", "user-value");
         final var correlated = resource.correlate("unique", "message-value");
         final var signaled = resource.signal("signal-value")) {
      assertThat(completed.getStatus()).isEqualTo(204);
      assertThat(correlated.getStatus()).isEqualTo(204);
      assertThat(signaled.getStatus()).isEqualTo(204);
    }
    final var command = ArgumentCaptor.forClass(CompleteTaskCmd.class);
    verify(completion).completeTask(command.capture());
    assertThat(command.getValue().getTaskId()).isEqualTo("123");
    assertThat(command.getValue().get()).isEqualTo(Map.of("some-user-value", "user-value"));
    verify(workflow).correlate("unique", "message-value");
    verify(workflow).signal("signal-value");
  }

  @Test
  void unsupportedNativeErrorDoesNotCompleteOrRemoveTheTask() {
    final var support = new UserTaskSupport();
    final var task = new TaskInformation("123", Map.of("assignee", "kermit"));
    support.accept(task, Map.of("intValue", 123));
    when(tasks.users()).thenReturn(support);
    try (final var response = resource.error("123", "business-error")) {
      assertThat(response.getStatus()).isEqualTo(501);
      assertThat(response.getEntity().toString()).contains("not supported");
    }
    assertThat(resource.tasks()).containsExactly(task);
    assertThat(support.getPayload("123")).containsEntry("intValue", 123);
    verifyNoInteractions(completion);
  }
}
