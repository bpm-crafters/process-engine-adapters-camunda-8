package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.it;

import dev.bpmcrafters.processengineapi.correlation.*;
import dev.bpmcrafters.processengineapi.decision.*;
import dev.bpmcrafters.processengineapi.deploy.*;
import dev.bpmcrafters.processengineapi.process.*;
import dev.bpmcrafters.processengineapi.task.*;
import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.PullUserTaskDelivery;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** HTTP boundary deliberately invokes the injected adapters inside both JVM and native applications. */
@Path("/adapter")
@Produces(MediaType.APPLICATION_JSON)
public class ProcessResource {
  @Inject DeploymentApi deployments;
  @Inject StartProcessApi processes;
  @Inject CorrelationApi correlation;
  @Inject SignalApi signals;
  @Inject EvaluateDecisionApi decisions;
  @Inject ServiceTaskCompletionApi services;
  @Inject UserTaskCompletionApi users;
  @Inject UserTaskModificationApi modifications;
  @Inject TaskHistory history;
  @Inject Instance<PullUserTaskDelivery> customDelivery;

  @GET @Path("/strategy")
  public Map<String, String> strategy() {
    return Map.of("strategy", TaskHistory.strategy());
  }

  @POST @Path("/deploy")
  public Map<String, String> deploy() throws Exception {
    String key = "";
    for (final var path : List.of("bpmn/simple-process.bpmn", "decision/main_decision.dmn",
        "example/simple-process.bpmn", "bpmn/quarkus-message-start.bpmn",
        "bpmn/quarkus-signal-start.bpmn", "bpmn/quarkus-service-error.bpmn")) {
      try (final var stream = Objects.requireNonNull(getClass().getResourceAsStream("/" + path), path)) {
        final var resource = new NamedResource(path.substring(path.lastIndexOf('/') + 1), stream, Map.of());
        key = deployments.deploy(new DeployBundleCommand(List.of(resource), null)).join().getDeploymentKey();
      }
    }
    return Map.of("deploymentKey", key);
  }

  @POST @Path("/start/{definition}/{runId}")
  public Map<String, String> start(@PathParam("definition") final String definition,
      @PathParam("runId") final String runId) {
    final var result = processes.startProcess(new StartProcessByDefinitionCmd(definition, payload(runId), Map.of())).join();
    return Map.of("instanceId", result.getInstanceId());
  }

  private Map<String, Object> payload(final String runId) {
    return Map.of("runId", runId, "keep", true, "discard", "not-selected",
        "payload", new ApplicationPayload("nested", List.of(3, 5), true, 12.5));
  }

  @POST @Path("/message-start/{runId}")
  public void messageStart(@PathParam("runId") final String runId) {
    processes.startProcess(new StartProcessByMessageCmd("quarkus-start-message", payload(runId), Map.of())).join();
  }

  @POST @Path("/message-wait/{runId}")
  public Map<String, String> messageWait(@PathParam("runId") final String runId) {
    final var result = processes.startProcess(new StartProcessByDefinitionAtElementCmd("simple-process-1",
        "received_message", Map.of("stringValue", runId, "runId", runId), Map.of())).join();
    return Map.of("instanceId", result.getInstanceId());
  }

  @POST @Path("/correlate/{runId}")
  public void correlate(@PathParam("runId") final String runId) {
    correlation.correlateMessage(new CorrelateMessageCmd("message1", Map.of("correlated", true),
        Correlation.withKey(runId), Map.of())).join();
  }

  @POST @Path("/signal/{runId}")
  public void signal(@PathParam("runId") final String runId) {
    signals.sendSignal(new SendSignalCmd("quarkus-start-signal", () -> payload(runId), Map.of())).join();
  }

  @GET @Path("/decisions/{id}")
  public Map<String, Object> decisions(@PathParam("id") final int id) {
    final var inputs = Map.<String, Object>of("id", id, "amount", 5000);
    final var offers = decisions.evaluateDecision(new DecisionByRefEvaluationCommand(
        "CollectMOutputDecision", inputs, Map.of())).join().asList().stream()
        .map(value -> value.asType(Offer.class)).toList();
    final var scalar = decisions.evaluateDecision(new DecisionByRefEvaluationCommand(
        "SingleSOutputDecision", inputs, Map.of())).join().asSingle().asType(Integer.class);
    final var result = new java.util.HashMap<String, Object>();
    result.put("offers", offers);
    result.put("scalar", scalar);
    return result;
  }

  @GET @Path("/events/{runId}")
  public List<TaskHistory.Event> events(@PathParam("runId") final String runId) {
    return history.events(runId);
  }

  @POST @Path("/service/{taskId}/{action}")
  public void service(@PathParam("taskId") final String taskId, @PathParam("action") final String action) {
    switch (action) {
      case "complete" -> services.completeTask(new CompleteTaskCmd(taskId, Map.of("serviceDone", true))).join();
      case "retry" -> services.failTask(new FailTaskCmd(taskId, "retry-once", null, 2, Duration.ofSeconds(1))).join();
      case "incident" -> services.failTask(new FailTaskCmd(taskId, "no-retries", null, 0, Duration.ZERO)).join();
      case "error" -> services.completeTaskByError(new CompleteTaskByErrorCmd(taskId, "quarkus-error",
          "expected error", Map.of("errorHandled", true))).join();
      default -> throw new BadRequestException(action);
    }
  }

  @POST @Path("/user/{taskId}/complete")
  public void userComplete(@PathParam("taskId") final String taskId) {
    users.completeTask(new CompleteTaskCmd(taskId, Map.of("userDone", true))).join();
  }

  @POST @Path("/user/{taskId}/modify")
  public void userModify(@PathParam("taskId") final String taskId) {
    modifications.update(new CompositeModifyTaskCmd(taskId, List.of(
        new ChangeAssignmentModifyTaskCmd.SetCandidateUsersTaskCmd(taskId, List.of("alice", "bob")),
        new ChangeAssignmentModifyTaskCmd.SetCandidateGroupsTaskCmd(taskId, List.of("reviewers")),
        new ChangeDatesModifyTaskCmd.SetDueDateTaskCmd(taskId, OffsetDateTime.parse("2035-01-02T12:00:00Z")),
        new ChangeDatesModifyTaskCmd.SetFollowUpDateTaskCmd(taskId, OffsetDateTime.parse("2035-01-01T12:00:00Z"))
    ))).join();
  }

  @POST @Path("/user/pull")
  public void pull() {
    if (!TaskHistory.strategy().equals("CUSTOM")) throw new BadRequestException("CUSTOM only");
    customDelivery.get().refresh();
  }

  @POST @Path("/user/unsubscribe")
  public void unsubscribe() {
    history.unsubscribeUser();
  }

  @RegisterForReflection
  public record ApplicationPayload(String label, List<Integer> numbers, boolean enabled, double amount) {}

  @RegisterForReflection
  public record Offer(double score, String message) {}
}
