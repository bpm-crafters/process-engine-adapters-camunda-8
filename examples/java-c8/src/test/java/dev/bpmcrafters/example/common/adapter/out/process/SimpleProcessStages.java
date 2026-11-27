package dev.bpmcrafters.example.common.adapter.out.process;

import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.annotation.Quoted;
import dev.bpmcrafters.example.common.adapter.shared.SimpleProcessWorkflowConst;
import dev.bpmcrafters.example.common.adapter.shared.SimpleProcessWorkflowConst.Elements;
import dev.bpmcrafters.example.common.adapter.shared.SimpleProcessWorkflowConst.Expressions;
import dev.bpmcrafters.processengineapi.correlation.Correlation;
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd;
import dev.bpmcrafters.processengineapi.testing.jgiven.ActionStage;
import dev.bpmcrafters.processengineapi.testing.jgiven.AssertStage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** JGiven stages backed by the public Process Engine API testing support. */
public class SimpleProcessStages {
  static class SimpleActionStage extends ActionStage<SimpleActionStage> {
    private String correlationValue;

    @As("simple process started with value $value and intValue $intValue")
    public SimpleActionStage simpleProcessStarted(@Quoted String value, @Quoted Integer intValue) {
      correlationValue = value;
      return processIsStartedByDefinition(
        SimpleProcessWorkflowConst.KEY,
        Map.of("stringValue", value, "intValue", intValue, "listVariable", List.of("element1", "element2"))
      );
    }

    @As("service task execute action completed with $value")
    public SimpleActionStage serviceExecuteActionIsCompleted(@Quoted String value) {
      return externalTaskIsCompleted(Expressions.JOB_TYPE_EXECUTE_ACTION_EXTERNAL, Map.of("action1", value));
    }

    @As("service task execute action completed with error")
    public SimpleActionStage serviceExecuteActionIsCompletedWithError() {
      externalTaskIsSelected(Expressions.JOB_TYPE_EXECUTE_ACTION_EXTERNAL);
      runtimeApis().serviceTaskCompletionApi().completeTaskByError(new CompleteTaskByErrorCmd(requireCurrentTask().getTaskId(), Expressions.ERROR_ACTION_ERROR, null, Map.of())).join();
      return self();
    }

    @As("service task execute action failed")
    public SimpleActionStage serviceExecuteActionIsFailed(int retries) {
      externalTaskIsSelected(Expressions.JOB_TYPE_EXECUTE_ACTION_EXTERNAL);
      return externalTaskHasFailed(Expressions.FAILURE_REASON, retries);
    }

    @As("user task perform task completed with $value")
    public SimpleActionStage userTaskPerformTaskIsCompleted(String value) {
      processWaitsInUserTask(Elements.USER_TASK_PERFORM_TASK);
      return userTaskIsCompleted(Map.of("some-user-value", value));
    }

    @As("user task variables are verified")
    public SimpleActionStage userTaskVariablesAreVerified() {
      processWaitsInUserTask(Elements.USER_TASK_PERFORM_TASK);
      Map<String, ?> actualVariables = payloadApi().getTaskPayload(requireCurrentTask().getTaskId());
      assertThat(actualVariables).containsKeys("intValue", "stringValue", "listVariable", "action1");
      assertThat(actualVariables.get("intValue")).isEqualTo(123);
      assertThat(actualVariables.get("stringValue")).isEqualTo("test");
      assertThat(actualVariables.get("listVariable")).isEqualTo(List.of("element1", "element2"));
      assertThat(actualVariables.get("action1")).isEqualTo("value1");
      return self();
    }

    @As("user task perform task is timed out")
    public SimpleActionStage userTaskPerformTaskIsTimedOut() {
      processWaitsInUserTask(Elements.USER_TASK_PERFORM_TASK);
      return timePasses(Duration.ofSeconds(61));
    }

    @As("user task perform task is complete with an error with $value")
    public SimpleActionStage userTaskPerformTaskIsCompletedWithError(String value) {
      processWaitsInUserTask(Elements.USER_TASK_PERFORM_TASK);
      runtimeApis().userTaskCompletionApi().completeTaskByError(new CompleteTaskByErrorCmd(requireCurrentTask().getTaskId(), "user_error", "some error", Map.of("some-user-value", value))).join();
      return self();
    }

    @As("service task message send completed")
    public SimpleActionStage serviceSendEmailIsCompleted() { return externalTaskIsCompleted(Expressions.JOB_TYPE_SEND_MESSAGE_EXTERNAL); }

    @As("message received with $value")
    public SimpleActionStage messageReceived(String value) {
      processWaitsInElement(Elements.EVENT_RECEIVED_MESSAGE);
      return messageIsCorrelated(Expressions.MESSAGE_1, Correlation.withKey(correlationValue), Map.of("message-delivered-value", value));
    }

    @As("signal occurred")
    public SimpleActionStage signalOccurred() {
      processWaitsInElement(Elements.EVENT_SIGNAL_OCCURRED);
      return signalIsSent(Expressions.SIGNAL_1, Map.of("signal-delivered-value", correlationValue));
    }
  }

  static class SimpleAssertStage extends AssertStage<SimpleAssertStage> { }
}
