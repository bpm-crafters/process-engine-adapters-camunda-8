package dev.bpmcrafters.example.common.adapter.out.process;

import dev.bpmcrafters.example.common.adapter.shared.SimpleProcessWorkflowConst;
import dev.bpmcrafters.example.common.adapter.shared.SimpleProcessWorkflowConst.Elements;
import dev.bpmcrafters.processengineapi.testing.config.UseProcessTestInitializer;
import dev.bpmcrafters.processengineapi.testing.jgiven.ProcessScenarioTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@UseProcessTestInitializer(qualifier = "c8")
public class SimpleProcessTest extends ProcessScenarioTest<SimpleProcessStages.SimpleActionStage, SimpleProcessStages.SimpleAssertStage> {
  @BeforeEach
  public void setup() { given().processIsDeployed(SimpleProcessWorkflowConst.BPMN); }

  @Test
  public void shouldStartProcessAndRunHappyPath() {

    given()
      .simpleProcessStarted("test", 123)
    ;

    when()
      .serviceExecuteActionIsCompleted("value1")
      .and()
      .userTaskPerformTaskIsCompleted("user-task-value")
      .and()
      .serviceSendEmailIsCompleted()
      .and()
      .messageReceived("message-value")
    ;

    then()
      .processIsFinished()
      .and()
      .processHasPassed(Elements.END_EVENT)
    ;
  }

  @Test
  public void shouldVerifyUserTaskVariables() {
    given()
      .simpleProcessStarted("test", 123)
    ;

    when()
      .serviceExecuteActionIsCompleted("value1")
      .and()
      .userTaskVariablesAreVerified()
    ;
  }

  @Test
  public void shouldStartProcessAndUseRetriesIfServiceTaskFails() {
    given()
      .simpleProcessStarted("test", 123)
    ;

    when()
      .serviceExecuteActionIsFailed(1)
      .and()
      .serviceExecuteActionIsFailed(0)
    ;

    then()
      .processHasIncidents(java.util.Map.of())
    ;
  }

  @Test
  public void shouldStartProcessAndHandleServiceTaskError() {
    given()
      .simpleProcessStarted("test", 123)
    ;

    when()
      .serviceExecuteActionIsCompletedWithError()
      .and()
      .signalOccurred()
    ;

    then()
      .processIsFinished()
      .and()
      .processHasPassed(Elements.END_EVENT_ABNORMALLY)
    ;
  }

  @Test
  public void shouldStartProcessAndHandleUserTaskTimeout() {
    given()
      .simpleProcessStarted("test", 123)
    ;

    when()
      .serviceExecuteActionIsCompleted("value1")
      .and()
      .userTaskPerformTaskIsTimedOut()
      .and()
      .signalOccurred()
    ;

    then()
      .processIsFinished()
      .and()
      .processHasPassed(Elements.END_EVENT_ABNORMALLY)
    ;
  }

  @Test
  @Disabled("not working for native user tasks")
  public void shouldStartProcessAndHandleUserTaskError() {
    given()
      .simpleProcessStarted("test", 123)
    ;

    when()
      .serviceExecuteActionIsCompleted("value1")
      .and()
      .userTaskPerformTaskIsCompletedWithError("task error value")
      .and()
      .signalOccurred()
    ;

    then()
      .processIsFinished()
      .and()
      .processHasPassed(Elements.END_EVENT_ABNORMALLY)
    ;
  }
}
