package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(CamundaTestResource.class)
public class ProcessResourceTest {
  private final List<String> instances = new ArrayList<>();
  private String strategy;

  @BeforeEach
  void deployFixturesAndCheckSelectedStrategy() {
    assertThat(post("/adapter/deploy").getString("deploymentKey")).matches("[1-9][0-9]*");
    strategy = get("/adapter/strategy").getString("strategy");
    assertThat(strategy).isIn("SCHEDULED", "SUBSCRIPTION_REFRESHING", "LISTENER", "CUSTOM");
    final var requested = System.getProperty("quarkus.process-engine-api.c8.user-tasks.delivery-strategy");
    if (requested != null) assertThat(strategy).isEqualTo(requested);
  }

  @AfterEach
  void cancelDisposableInstances() {
    for (final var instance : instances) {
      if (get("/engine/instance/" + instance).getString("state").equals("ACTIVE")) {
        command("/engine/cancel/" + instance);
      }
    }
  }

  @Test
  void evaluatesTypedDecisionsInsideTheRunningApplication() {
    final var result = get("/adapter/decisions/99");
    assertThat(result.getInt("scalar")).isEqualTo(10);
    assertThat(result.getDouble("offers[0].score")).isEqualTo(23.5);
    assertThat(result.getDouble("offers[1].score")).isEqualTo(43.0);
    assertThat(result.getList("offers.message", String.class)).containsExactly("no-PIN", "OK");
    final var unmatched = get("/adapter/decisions/1000");
    assertThat(unmatched.getList("offers")).isEmpty();
    assertThat(unmatched.<Object>get("scalar")).isNull();
  }

  @Test
  void startsByMessageAndBroadcastSignalWithObservablePayload() {
    final var messageRun = token();
    command("/adapter/message-start/" + messageRun);
    completeStartedEvent(messageRun, "quarkus-message-start", "RecordMessage", "MessageRecorded");
    final var signalRun = token();
    command("/adapter/signal/" + signalRun);
    completeStartedEvent(signalRun, "quarkus-signal-start", "RecordSignal", "SignalRecorded");
  }

  private void completeStartedEvent(final String run, final String definition,
      final String element, final String end) {
    final var event = delivered(run, element);
    assertPayloadAndMetadata(event, run, element);
    final var instance = event.meta().get(CommonRestrictions.PROCESS_INSTANCE_ID);
    instances.add(instance);
    eventually(() -> assertThat(get("/engine/instance/" + instance).getString("definition")).isEqualTo(definition));
    variable(instance, "runId", "\"" + run + "\"");
    command("/adapter/service/" + event.taskId() + "/complete");
    terminated(run, event.taskId(), TaskInformation.COMPLETE);
    completedElement(instance, end);
    eventually(() -> assertThat(get("/engine/instance/" + instance).getString("state")).isEqualTo("COMPLETED"));
  }

  @Test
  void startsAtMessageCatchAndCorrelatesTheWaitingInstance() {
    final var run = token();
    final var instance = post("/adapter/message-wait/" + run).getString("instanceId");
    instances.add(instance);
    eventually(() -> assertThat(get("/engine/message-wait/" + instance + "/" + run).getInt("count")).isEqualTo(1));
    command("/adapter/correlate/" + run);
    completedElement(instance, "received_message");
    completedElement(instance, "finished");
    variable(instance, "correlated", "true");
  }

  @Test
  void serviceSubscriptionFiltersPayloadAndCompletesTheActualElement() {
    final var run = token();
    final var instance = start("simple-process", run);
    final var event = delivered(run, "service-do-action1");
    assertPayloadAndMetadata(event, run, "service-do-action1");
    command("/adapter/service/" + event.taskId() + "/complete");
    terminated(run, event.taskId(), TaskInformation.COMPLETE);
    completedElement(instance, "service-do-action1");
    eventually(() -> assertThat(get("/engine/job/" + event.taskId()).getString("state")).isEqualTo("COMPLETED"));
    variable(instance, "serviceDone", "true");
    // The fixture has three parallel branches; completing one service does not finish the process.
    assertThat(get("/engine/instance/" + instance).getString("state")).isEqualTo("ACTIVE");
  }

  @Test
  void serviceNormalRetryAndBpmnErrorPathsReachTheirModeledEnds() {
    for (final var action : List.of("complete", "retry", "error")) {
      final var run = token();
      final var instance = start("quarkus-service-error", run);
      final var event = delivered(run, "ExecuteService");
      command("/adapter/service/" + event.taskId() + "/" + action);
      terminated(run, event.taskId(), TaskInformation.COMPLETE);
      if (action.equals("retry")) retryAndComplete(run, event.taskId());
      completedElement(instance, action.equals("error") ? "ErrorHandled" : "ServiceCompleted");
      variable(instance, action.equals("error") ? "errorHandled" : "serviceDone", "true");
      eventually(() -> assertThat(get("/engine/instance/" + instance).getString("state")).isEqualTo("COMPLETED"));
    }
  }

  private void retryAndComplete(final String run, final String taskId) {
    eventually(() -> assertThat(events(run).stream().filter(e -> e.taskId().equals(taskId)
        && e.reason().equals(TaskInformation.CREATE)).count()).isGreaterThanOrEqualTo(2));
    command("/adapter/service/" + taskId + "/complete");
    eventually(() -> assertThat(events(run).stream().filter(e -> e.taskId().equals(taskId)
        && e.reason().equals(TaskInformation.COMPLETE)).count()).isGreaterThanOrEqualTo(2));
  }

  @Test
  void serviceTechnicalFailureCreatesAnActualNoRetriesIncident() {
    final var run = token();
    start("quarkus-service-error", run);
    final var event = delivered(run, "ExecuteService");
    command("/adapter/service/" + event.taskId() + "/incident");
    terminated(run, event.taskId(), TaskInformation.COMPLETE);
    eventually(() -> assertThat(get("/engine/incident/" + event.taskId()).getList("", String.class))
        .contains("JOB_NO_RETRIES"));
    eventually(() -> {
      final var job = get("/engine/job/" + event.taskId());
      assertThat(job.getInt("retries")).isZero();
      assertThat(job.getString("errorMessage")).isEqualTo("no-retries");
    });
  }

  @Test
  void selectedUserStrategyDeliversModifiesCompletesCancelsAndUnsubscribes() {
    final var run = token();
    final var instance = start("simple-process", run);
    final var element = strategy.equals("SUBSCRIPTION_REFRESHING")
        ? "user-perform-task-job-based" : "user-perform-task";
    if (strategy.equals("CUSTOM")) assertCustomIsManual(instance, run, element);
    final var event = delivered(run, element);
    assertPayloadAndMetadata(event, run, element);
    assertThat(event.meta().get(CommonRestrictions.PROCESS_INSTANCE_ID)).isEqualTo(instance);
    if (strategy.equals("SUBSCRIPTION_REFRESHING")) assertScheduledLockRefresh(run, event.taskId());
    modifyNativeUser(instance, event, run);
    command("/adapter/user/" + event.taskId() + "/complete");
    terminated(run, event.taskId(), TaskInformation.COMPLETE);
    completedElement(instance, element);
    variable(instance, "userDone", "true");
    if (!strategy.equals("SUBSCRIPTION_REFRESHING")) {
      eventually(() -> assertThat(get("/engine/user/" + event.taskId()).getString("state")).isEqualTo("COMPLETED"));
    }
    assertUserCancellation(element);
    if (strategy.equals("LISTENER")) assertOutOfBandListenerCompletion(element);
    assertUserUnsubscribe(element);
  }

  private void assertCustomIsManual(final String instance, final String run, final String element) {
    final var taskId = nativeUser(instance);
    eventually(() -> assertThat(get("/engine/user/" + taskId).getString("state")).isEqualTo("CREATED"));
    await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(events(run).stream().filter(e -> element.equals(e.element()))).isEmpty());
    command("/adapter/user/pull");
  }

  private void modifyNativeUser(final String instance, final Event event, final String run) {
    final var nativeId = strategy.equals("SUBSCRIPTION_REFRESHING") ? nativeUser(instance) : event.taskId();
    command("/adapter/user/" + nativeId + "/modify");
    eventually(() -> {
      final var task = get("/engine/user/" + nativeId);
      assertThat(task.getList("candidateUsers", String.class)).containsExactlyInAnyOrder("alice", "bob");
      assertThat(task.getList("candidateGroups", String.class)).containsExactly("reviewers");
      final var dueDate = task.getString("dueDate");
      final var followUpDate = task.getString("followUpDate");
      assertThat(dueDate).as("task %s exported dueDate", nativeId).isNotBlank();
      assertThat(followUpDate).as("task %s exported followUpDate", nativeId).isNotBlank();
      assertThat(OffsetDateTime.parse(dueDate)).isEqualTo(OffsetDateTime.parse("2035-01-02T12:00:00Z"));
      assertThat(OffsetDateTime.parse(followUpDate)).isEqualTo(OffsetDateTime.parse("2035-01-01T12:00:00Z"));
    });
    if (strategy.equals("SCHEDULED") || strategy.equals("LISTENER")) terminated(run, nativeId, TaskInformation.UPDATE);
    if (strategy.equals("LISTENER")) {
      assertThat(get("/engine/listener").getString("type")).isEqualTo("process-engine-user-tasks");
      assertThat(get("/engine/listener").getList("events", String.class))
          .containsExactly("ALL");
      assertThat(get("/engine/listener").getInt("retries")).isEqualTo(3);
      command("/engine/user/" + nativeId + "/assign");
      terminated(run, nativeId, TaskInformation.ASSIGN);
    }
  }

  private void assertScheduledLockRefresh(final String run, final String taskId) {
    final var original = await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(50))
        .until(() -> jobDeadline(taskId), deadline -> deadline != null
            && deadline.isAfter(Instant.now().plusMillis(1500)));
    assertSingleCreate(run, taskId);
    // A later deadline observed before expiry cannot be explained by timeout/reactivation.
    await().atMost(Duration.between(Instant.now(), original)).pollInterval(Duration.ofMillis(50))
        .untilAsserted(() -> {
          assertThat(jobDeadline(taskId)).isAfter(original);
          assertSingleCreate(run, taskId);
          assertThat(Instant.now()).isBefore(original);
        });
    assertNoReactivationPastOriginalExpiry(run, taskId, original);
  }

  private void assertNoReactivationPastOriginalExpiry(final String run, final String taskId, final Instant original) {
    await().atMost(Duration.ofSeconds(6)).pollInterval(Duration.ofMillis(100)).until(() -> {
      assertSingleCreate(run, taskId);
      return Instant.now().isAfter(original.plusSeconds(1));
    });
    // Exported deadlines may lag renewal; callback history must never show reactivation.
    await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250)).until(() -> {
      assertSingleCreate(run, taskId);
      final var deadline = jobDeadline(taskId);
      return deadline != null && deadline.isAfter(original.plusSeconds(1));
    });
    assertSingleCreate(run, taskId);
  }

  private Instant jobDeadline(final String taskId) {
    final var deadline = get("/engine/job/" + taskId).getString("deadline");
    return deadline == null ? null : OffsetDateTime.parse(deadline).toInstant();
  }

  private void assertSingleCreate(final String run, final String taskId) {
    assertThat(events(run).stream().filter(e -> taskId.equals(e.taskId())
        && TaskInformation.CREATE.equals(e.reason())).count()).as("task %s must not be reactivated", taskId).isEqualTo(1);
  }

  private void assertUserCancellation(final String element) {
    final var run = token();
    final var instance = start("simple-process", run);
    final var task = delivered(run, element);
    command("/engine/cancel/" + instance);
    terminated(run, task.taskId(), TaskInformation.DELETE);
    eventually(() -> assertThat(get("/engine/instance/" + instance).getString("state")).isEqualTo("TERMINATED"));
  }

  private void assertOutOfBandListenerCompletion(final String element) {
    final var run = token();
    final var instance = start("simple-process", run);
    final var task = delivered(run, element);
    command("/engine/user/" + task.taskId() + "/complete");
    terminated(run, task.taskId(), TaskInformation.COMPLETE);
    completedElement(instance, element);
    variable(instance, "rawCompleted", "true");
  }

  private void assertUserUnsubscribe(final String element) {
    command("/adapter/user/unsubscribe");
    final var run = token();
    final var instance = start("simple-process", run);
    // Wait for the engine's native branch, then observe longer than the delivery/refresh interval.
    nativeUser(instance);
    await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
      if (strategy.equals("CUSTOM")) command("/adapter/user/pull");
      assertThat(events(run).stream().filter(e -> element.equals(e.element()))).isEmpty();
    });
  }

  private String nativeUser(final String instance) {
    eventually(() -> assertThat(get("/engine/native-user/" + instance).getList("", String.class)).hasSize(1));
    return get("/engine/native-user/" + instance).getList("", String.class).get(0);
  }

  private void assertPayloadAndMetadata(final Event event, final String run, final String element) {
    assertThat(event.reason()).isEqualTo(TaskInformation.CREATE);
    assertThat(event.meta()).containsEntry(CommonRestrictions.ACTIVITY_ID, element);
    assertThat(event.meta().get(CommonRestrictions.PROCESS_INSTANCE_ID)).matches("[1-9][0-9]*");
    assertThat(event.variables()).containsOnlyKeys("runId", "keep", "payload")
        .containsEntry("runId", run).containsEntry("keep", true);
    final var nested = (Map<?, ?>) event.variables().get("payload");
    assertThat(nested.get("label")).isEqualTo("nested");
    assertThat(nested.get("text")).isEqualTo("large-payload-".repeat(1400));
    assertThat(nested.get("numbers")).isEqualTo(List.of(3, 5));
    assertThat(nested.get("enabled")).isEqualTo(true);
    assertThat(((Number) nested.get("amount")).doubleValue()).isEqualTo(12.5);
  }

  private String start(final String definition, final String run) {
    final var instance = post("/adapter/start/" + definition + "/" + run).getString("instanceId");
    assertThat(instance).matches("[1-9][0-9]*");
    instances.add(instance);
    return instance;
  }

  private Event delivered(final String run, final String element) {
    eventually(() -> {
      if (strategy.equals("CUSTOM")) command("/adapter/user/pull");
      assertThat(events(run).stream().filter(e -> element.equals(e.element())
          && TaskInformation.CREATE.equals(e.reason()))).isNotEmpty();
    });
    return events(run).stream().filter(e -> element.equals(e.element())
        && TaskInformation.CREATE.equals(e.reason())).findFirst().orElseThrow();
  }

  private void terminated(final String run, final String task, final String reason) {
    eventually(() -> {
      if (strategy.equals("CUSTOM")) command("/adapter/user/pull");
      assertThat(events(run).stream().filter(e -> task.equals(e.taskId()) && reason.equals(e.reason())))
          .as("task %s callback %s", task, reason).isNotEmpty();
    });
  }

  private void completedElement(final String instance, final String element) {
    eventually(() -> assertThat(get("/engine/instance/" + instance).<Map<String, String>>getList("elements"))
        .contains(Map.of("elementId", element, "state", "COMPLETED")));
  }

  private void variable(final String instance, final String name, final String value) {
    eventually(() -> assertThat(get("/engine/instance/" + instance).getMap("variables"))
        .containsEntry(name, value));
  }

  private List<Event> events(final String run) {
    return get("/adapter/events/" + run).<Map<String, Object>>getList("").stream().map(Event::from).toList();
  }

  private static void eventually(final org.awaitility.core.ThrowingRunnable assertion) {
    await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250)).untilAsserted(assertion);
  }

  private static String token() { return UUID.randomUUID().toString(); }
  private static JsonPath get(final String path) {
    return given().when().get(path).then().statusCode(200).extract().jsonPath();
  }
  private static JsonPath post(final String path) {
    return given().when().post(path).then().statusCode(200).extract().jsonPath();
  }
  private static void command(final String path) {
    given().when().post(path).then().statusCode(204);
  }

  private record Event(String taskId, Map<String, String> meta, Map<String, Object> variables) {
    @SuppressWarnings("unchecked")
    static Event from(final Map<String, Object> source) {
      return new Event((String) source.get("taskId"), (Map<String, String>) source.get("meta"),
          (Map<String, Object>) source.get("variables"));
    }
    String element() { return meta.get(CommonRestrictions.ACTIVITY_ID); }
    String reason() { return meta.get(TaskInformation.REASON); }
  }
}
