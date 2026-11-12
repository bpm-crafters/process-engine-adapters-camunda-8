package dev.bpmcrafters.example.javac8quarkus.adapter.in.process;

import static dev.bpmcrafters.example.javac8quarkus.ExampleTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.bpmcrafters.processengineapi.Empty;
import dev.bpmcrafters.processengineapi.task.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskSubscriptionsTest {
  private final TaskSubscriptionApi api = mock(TaskSubscriptionApi.class);
  private final ServiceTaskCompletionApi completion = mock(ServiceTaskCompletionApi.class);
  private final TaskSubscriptions subscriptions = new TaskSubscriptions(api, completion);

  @BeforeEach
  void completionsSucceed() {
    when(completion.completeTask(any())).thenReturn(CompletableFuture.completedFuture(Empty.INSTANCE));
    when(completion.completeTaskByError(any())).thenReturn(CompletableFuture.completedFuture(Empty.INSTANCE));
    when(completion.failTask(any())).thenReturn(CompletableFuture.completedFuture(Empty.INSTANCE));
  }

  @Test
  void registersBothUnrestrictedWorkersBeforeUserDeliveryAndUnregistersAll() {
    final List<TaskSubscription> handles = new ArrayList<>();
    when(api.subscribeForTask(any())).thenAnswer(invocation -> {
      final TaskSubscription handle = new TaskSubscription() {};
      handles.add(handle);
      return CompletableFuture.completedFuture(handle);
    });
    when(api.unsubscribe(any())).thenReturn(CompletableFuture.completedFuture(Empty.INSTANCE));
    subscriptions.start(null);
    final var commands = ArgumentCaptor.forClass(SubscribeForTaskCmd.class);
    verify(api, times(3)).subscribeForTask(commands.capture());
    final var registered = commands.getAllValues();
    assertThat(registered).extracting(SubscribeForTaskCmd::getTaskDescriptionKey)
        .containsExactly("execute-action-external", "send-message-external", null);
    assertThat(registered).extracting(SubscribeForTaskCmd::getTaskType)
        .containsExactly(TaskType.EXTERNAL, TaskType.EXTERNAL, TaskType.USER);
    assertThat(registered).allSatisfy(command -> {
      assertThat(command.getRestrictions()).isEmpty();
      assertThat(command.getPayloadDescription()).isNull();
    });
    assertWorkerRouting(registered);
    assertUserCache(registered.get(2));
    subscriptions.stop(null);
    final var removed = ArgumentCaptor.forClass(UnsubscribeFromTaskCmd.class);
    verify(api, times(3)).unsubscribe(removed.capture());
    assertThat(removed.getAllValues()).extracting(UnsubscribeFromTaskCmd::getSubscription).containsExactlyElementsOf(handles);
  }

  private void assertWorkerRouting(final List<SubscribeForTaskCmd> registered) {
    registered.get(0).getAction().accept(task(3), payload(123));
    registered.get(1).getAction().accept(task(3), payload(123));
    final var completed = ArgumentCaptor.forClass(CompleteTaskCmd.class);
    verify(completion, times(2)).completeTask(completed.capture());
    assertThat(completed.getAllValues().get(0).get()).containsEntry("action1", "value1");
    assertThat(completed.getAllValues().get(1).get()).containsEntry("action2", "value2");
  }

  private void assertUserCache(final SubscribeForTaskCmd command) {
    final var data = payload(123);
    command.getAction().accept(task(3), data);
    assertThat(subscriptions.users().getAllTasks()).containsExactly(task(3));
    assertThat(subscriptions.users().getPayload("123")).isEqualTo(data);
    command.getTermination().accept(task(3));
    assertThat(subscriptions.users().getAllTasks()).isEmpty();
    assertThatThrownBy(() -> subscriptions.users().getPayload("123")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void copiesTheNormalPayloadWithoutMutatingInput() {
    final var input = payload(123);
    subscriptions.handle("execute-action-external", task(3), input);
    final var completed = ArgumentCaptor.forClass(CompleteTaskCmd.class);
    verify(completion).completeTask(completed.capture());
    assertThat(completed.getValue().getTaskId()).isEqualTo("123");
    assertThat(completed.getValue().get()).containsAllEntriesOf(input).containsEntry("action1", "value1");
    assertThat(input).doesNotContainKey("action1");
    verify(completion, never()).failTask(any());
  }

  @Test
  void multiplesOf42ReportBusinessErrorNotTechnicalFailure() {
    for (final int number : List.of(0, 42, -84)) {
      subscriptions.handle("execute-action-external", task(3), payload(number));
    }
    final var errors = ArgumentCaptor.forClass(CompleteTaskByErrorCmd.class);
    verify(completion, times(3)).completeTaskByError(errors.capture());
    assertThat(errors.getAllValues()).allSatisfy(command -> {
      assertThat(command.getErrorCode()).isEqualTo("action_error");
      assertThat(command.getTaskId()).isEqualTo("123");
      assertThat(command.get()).isEmpty();
    });
    verify(completion, never()).completeTask(any());
    verify(completion, never()).failTask(any());
  }

  @Test
  void invalidPayloadReducesRetriesAndMissingMetadataUsesTheDefaultSupplier() {
    for (final var retries : List.of(3, 2, 1)) {
      subscriptions.handle("execute-action-external", task(retries), payload("not-an-integer"));
    }
    subscriptions.handle("execute-action-external", task(null), Map.of());
    final var failures = ArgumentCaptor.forClass(FailTaskCmd.class);
    verify(completion, times(4)).failTask(failures.capture());
    assertThat(failures.getAllValues()).extracting(FailTaskCmd::getRetryCount).containsExactly(2, 1, 0, null);
    assertThat(failures.getAllValues()).allSatisfy(command -> {
      assertThat(command.getTaskId()).isEqualTo("123");
      assertThat(command.getReason()).isNotBlank();
      assertThat(command.getRetryBackoff()).isNull();
    });
    verify(completion, never()).completeTask(any());
  }

  @Test
  void observesFailedCompletionFuturesAndDoesNotSwallowFailureReporting() {
    when(completion.completeTask(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("completion failed")));
    subscriptions.handle("send-message-external", task(3), payload(123));
    verify(completion).failTask(argThat(command -> command.getRetryCount() == 2 && command.getReason().contains("completion failed")));
    when(completion.failTask(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("report failed")));
    assertThatThrownBy(() -> subscriptions.handle("send-message-external", task(3), payload(123)))
        .hasRootCauseMessage("report failed");
  }

  @Test
  void doesNotTreatFatalErrorsAsTechnicalFailures() {
    when(completion.completeTask(any())).thenThrow(new AssertionError("fatal"));
    assertThatThrownBy(() -> subscriptions.handle("send-message-external", task(3), payload(123)))
        .isInstanceOf(AssertionError.class);
    verify(completion, never()).failTask(any());
  }

  @Test
  void stoppingBeforeStartupDoesNotUnsubscribeUnknownHandles() {
    subscriptions.stop(null);
    verifyNoInteractions(api);
  }
}
