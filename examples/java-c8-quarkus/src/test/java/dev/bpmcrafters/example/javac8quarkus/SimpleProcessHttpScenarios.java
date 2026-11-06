package dev.bpmcrafters.example.javac8quarkus;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.IncidentState;
import io.camunda.client.api.search.enums.MessageSubscriptionState;
import io.restassured.path.json.JsonPath;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Shared HTTP assertions for both the in-process application and the packaged JVM. */
public abstract class SimpleProcessHttpScenarios {
  private static final String BASE = "/simple-service-tasks";
  private final List<Long> instances = new ArrayList<>();
  // Injected by the test resource, never CDI: also works against the packaged JVM.
  CamundaClient observer;

  @BeforeEach
  void deployTheUnmodifiedModelThroughHttp() {
    final var deployment = given().when().post(BASE + "/deploy").then().statusCode(201).extract().response();
    assertThat(deployment.asString()).isEmpty();
    assertThat(key(deployment.header("Location"))).isPositive();
  }

  @AfterEach
  void cancelOnlyInstancesCreatedByThisTest() {
    for (final var instance : instances) {
      eventually(() -> assertThat(observer.newProcessInstanceGetRequest(instance).send().join()).isNotNull());
      if (observer.newProcessInstanceGetRequest(instance).send().join().getState().name().equals("ACTIVE")) {
        observer.newCancelInstanceCommand(instance).send().join();
        eventually(() -> assertThat(observer.newProcessInstanceGetRequest(instance).send().join().getState().name())
            .isEqualTo("TERMINATED"));
      }
    }
  }

  @Test
  void actualWorkersListenerUserCompletionAndMessageReachTheHappyEnd() {
    final var run = UUID.randomUUID().toString();
    final var instance = start(run, 123);
    final var task = deliveredUser(instance);
    eventually(() -> assertAssignment(task, instance));
    assertInitialVariables(instance, run);
    given().queryParam("value", "not-supported").when().post(BASE + "/tasks/" + task.id() + "/error")
        .then().statusCode(501);
    assertThat(tasks(instance)).extracting(UserTask::id).containsExactly(task.id());
    assertThat(observer.newUserTaskGetRequest(Long.parseLong(task.id())).send().join().getState().name())
        .isIn("CREATED", "ASSIGNED");
    command("/tasks/" + task.id() + "/complete", "user-task-value");
    eventually(() -> assertThat(tasks(instance)).isEmpty());
    awaitMessage(instance, run);
    command("/correlate/" + run, "message-value");
    finished(instance, "finished");
    completedElement(instance, "service-do-action1");
    completedElement(instance, "service-do-action2");
    eventually(() -> assertThat(variables(instance)).containsEntry("action1", "value1")
        .containsEntry("action2", "value2").containsEntry("some-user-value", "user-task-value")
        .containsEntry("message-delivered-value", "message-value"));
  }

  private void assertAssignment(final UserTask task, final long instance) {
    assertThat(task.meta()).containsEntry("assignee", "kermit")
        .containsEntry(CommonRestrictions.ACTIVITY_ID, "user-perform-task")
        .containsEntry(CommonRestrictions.PROCESS_DEFINITION_KEY, "simple-process-1")
        .containsEntry(CommonRestrictions.PROCESS_INSTANCE_ID, Long.toString(instance));
    assertThat(task.meta().get("candidateUsers").split(",")).containsExactlyInAnyOrder("gonzo", "fozzy");
    assertThat(task.meta().get("candidateGroups")).isEqualTo("avengers");
    final var nativeTask = observer.newUserTaskGetRequest(Long.parseLong(task.id())).send().join();
    assertThat(nativeTask.getAssignee()).isEqualTo("kermit");
    assertThat(nativeTask.getCandidateUsers()).containsExactlyInAnyOrder("gonzo", "fozzy");
    assertThat(nativeTask.getCandidateGroups()).containsExactly("avengers");
  }

  private void assertInitialVariables(final long instance, final String run) {
    eventually(() -> assertThat(variables(instance)).containsEntry("stringValue", run)
        .containsEntry("intValue", 123).containsEntry("listVariable", List.of("element1", "element2"))
        .containsEntry("listIntVariable", List.of(12, 13, 15)).containsEntry("toBeOrNotToBe", true)
        .containsEntry("complex", Map.of("foo", "foo", "bar", "bar")));
  }

  @Test
  void automaticWorkerBusinessErrorAndSignalReachTheAbnormalEnd() {
    final var instance = start(UUID.randomUUID().toString(), 42);
    completedElement(instance, "action_error");
    activeElement(instance, "received_signal");
    assertThat(tasks(instance)).isEmpty();
    command("/signal", "business-signal-value");
    finished(instance, "finished_abnormally");
    eventually(() -> assertThat(variables(instance)).containsEntry("signal-delivered-value", "business-signal-value")
        .doesNotContainKeys("action1", "action2"));
    assertThat(observer.newIncidentSearchRequest().filter(f -> f.processInstanceKey(instance)
        .state(IncidentState.ACTIVE)).send().join().items()).isEmpty();
  }

  @Test
  void invalidTestSideEnginePayloadExhaustsActualAutomaticWorkerRetries() {
    // HTTP input validation prevents this malformed input; only the independent test client supplies it.
    final var instance = observer.newCreateInstanceCommand().bpmnProcessId("simple-process-1").latestVersion()
        .variables(Map.of("stringValue", UUID.randomUUID().toString(), "intValue", "not-an-integer"))
        .send().join().getProcessInstanceKey();
    instances.add(instance);
    eventually(() -> {
      final var incidents = observer.newIncidentSearchRequest().filter(f -> f.processInstanceKey(instance)
          .elementId("service-do-action1").state(IncidentState.ACTIVE)).send().join().items();
      assertThat(incidents).hasSize(1);
      assertThat(incidents.get(0).getErrorType().name()).isEqualTo("JOB_NO_RETRIES");
      final var jobs = observer.newJobSearchRequest().filter(f -> f.processInstanceKey(instance)
          .elementId("service-do-action1").type("execute-action-external")).send().join().items();
      assertThat(jobs).hasSize(1);
      assertThat(jobs.get(0).getRetries()).isZero();
      assertThat(jobs.get(0).getErrorMessage()).contains("ClassCastException");
    });
    assertThat(tasks(instance)).isEmpty();
  }

  @Test
  void realOneMinuteUserTimerRemovesTheCachedTaskAndSignalFinishesAbnormally() {
    final var instance = start(UUID.randomUUID().toString(), 123);
    final var task = deliveredUser(instance);
    await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> assertElement(instance, "timed_out", "COMPLETED"));
    activeElement(instance, "received_signal");
    eventually(() -> assertThat(tasks(instance)).isEmpty());
    eventually(() -> assertThat(observer.newUserTaskGetRequest(Long.parseLong(task.id())).send().join()
        .getState().name()).isEqualTo("CANCELED"));
    command("/signal", "timer-signal-value");
    finished(instance, "finished_abnormally");
    eventually(() -> assertThat(variables(instance)).containsEntry("signal-delivered-value", "timer-signal-value")
        .doesNotContainKeys("some-user-value", "action2"));
  }

  @Test
  void exposesPackagedSwaggerAndRejectsInvalidHttpInput() {
    given().when().get("/q/openapi").then().statusCode(200);
    given().redirects().follow(true).when().get("/q/swagger-ui/").then().statusCode(200);
    given().queryParam("value", "unique").when().post(BASE + "/start-process").then().statusCode(400);
    for (final var number : List.of("bad", "2147483648")) {
      given().queryParam("value", "unique").queryParam("intValue", number)
          .when().post(BASE + "/start-process").then().statusCode(400);
    }
    given().queryParam("intValue", 123).when().post(BASE + "/start-process").then().statusCode(400);
    given().when().post(BASE + "/signal").then().statusCode(400);
    given().when().post(BASE + "/correlate/unique").then().statusCode(400);
    given().when().post(BASE + "/tasks/123/complete").then().statusCode(400);
    given().when().post(BASE + "/tasks/123/error").then().statusCode(400);
  }

  private long start(final String run, final int number) {
    final var response = given().queryParam("value", run).queryParam("intValue", number)
        .when().post(BASE + "/start-process").then().statusCode(201).extract().response();
    assertThat(response.asString()).isEmpty();
    final var instance = key(response.header("Location"));
    instances.add(instance);
    return instance;
  }

  private static long key(final String location) {
    assertThat(location).isNotBlank();
    final var path = URI.create(location).getPath();
    return Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
  }

  private UserTask deliveredUser(final long instance) {
    eventually(() -> assertThat(tasks(instance)).hasSize(1));
    return tasks(instance).get(0);
  }

  private List<UserTask> tasks(final long instance) {
    return given().when().get(BASE + "/tasks").then().statusCode(200).extract().jsonPath()
        .<Map<String, Object>>getList("").stream().map(UserTask::from)
        .filter(task -> Long.toString(instance).equals(task.meta().get(CommonRestrictions.PROCESS_INSTANCE_ID)))
        .toList();
  }

  private void awaitMessage(final long instance, final String run) {
    eventually(() -> assertThat(observer.newMessageSubscriptionSearchRequest().filter(f -> f.processInstanceKey(instance)
        .messageName("message1").correlationKey(run).messageSubscriptionState(MessageSubscriptionState.CREATED))
        .send().join().items()).hasSize(1));
  }

  private Map<String, Object> variables(final long instance) {
    return observer.newVariableSearchRequest().filter(f -> f.processInstanceKey(instance)).withFullValues()
        .send().join().items().stream().collect(Collectors.toMap(v -> v.getName(),
            v -> JsonPath.from(v.getValue()).get(""), (first, second) -> second));
  }

  private void finished(final long instance, final String end) {
    completedElement(instance, end);
    eventually(() -> assertThat(observer.newProcessInstanceGetRequest(instance).send().join().getState().name())
        .isEqualTo("COMPLETED"));
  }

  private void completedElement(final long instance, final String element) {
    eventually(() -> assertElement(instance, element, "COMPLETED"));
  }

  private void activeElement(final long instance, final String element) {
    eventually(() -> assertElement(instance, element, "ACTIVE"));
  }

  private void assertElement(final long instance, final String element, final String state) {
    assertThat(observer.newElementInstanceSearchRequest().filter(f -> f.processInstanceKey(instance)
        .elementId(element)).send().join().items()).anySatisfy(item -> assertThat(item.getState().name()).isEqualTo(state));
  }

  private static void command(final String path, final String value) {
    given().queryParam("value", value).when().post(BASE + path).then().statusCode(204);
  }

  private static void eventually(final ThrowingRunnable assertion) {
    await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250))
        .ignoreExceptionsInstanceOf(CompletionException.class).untilAsserted(assertion);
  }

  private record UserTask(String id, Map<String, String> meta) {
    @SuppressWarnings("unchecked")
    static UserTask from(final Map<String, Object> source) {
      return new UserTask((String) source.get("taskId"), (Map<String, String>) source.get("meta"));
    }
  }
}
