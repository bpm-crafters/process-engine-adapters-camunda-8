package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.it;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.IncidentState;
import io.camunda.client.api.search.enums.MessageSubscriptionState;
import io.camunda.client.api.search.response.Variable;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/** Raw client reads verify persisted engine outcomes, independently of adapter callback history. */
@Path("/engine")
@Produces(MediaType.APPLICATION_JSON)
public class EngineObservationResource {
  @Inject CamundaClient client;

  @GET @Path("/instance/{key}")
  public Map<String, Object> instance(@PathParam("key") final long key) {
    final var process = client.newProcessInstanceGetRequest(key).send().join();
    final var elements = client.newElementInstanceSearchRequest().filter(f -> f.processInstanceKey(key))
        .send().join().items().stream()
        .map(e -> Map.of("elementId", e.getElementId(), "state", e.getState().name())).toList();
    final var variables = client.newVariableSearchRequest().filter(f -> f.processInstanceKey(key))
        .send().join().items().stream()
        .collect(java.util.stream.Collectors.toMap(v -> v.getName(), this::fullValue, (a, b) -> b));
    return Map.of("state", process.getState().name(), "definition", process.getProcessDefinitionId(),
        "elements", elements, "variables", variables);
  }

  private String fullValue(final Variable variable) {
    if (!Boolean.TRUE.equals(variable.isTruncated())) return variable.getValue();
    final var key = variable.getVariableKey();
    if (key == null || key <= 0) throw new IllegalStateException("Missing or invalid truncated variable key");
    return client.newVariableGetRequest(key).send().join().getValue();
  }

  @GET @Path("/message-wait/{key}/{runId}")
  public Map<String, Integer> messageWait(@PathParam("key") final long key,
      @PathParam("runId") final String runId) {
    final var count = client.newMessageSubscriptionSearchRequest().filter(f -> f.processInstanceKey(key)
        .messageName("message1").correlationKey(runId).messageSubscriptionState(MessageSubscriptionState.CREATED))
        .send().join().items().size();
    return Map.of("count", count);
  }

  @GET @Path("/user/{key}")
  public Map<String, Object> user(@PathParam("key") final long key) {
    final var task = client.newUserTaskGetRequest(key).send().join();
    final var result = new java.util.HashMap<String, Object>();
    result.put("state", task.getState().name());
    result.put("candidateUsers", task.getCandidateUsers());
    result.put("candidateGroups", task.getCandidateGroups());
    result.put("dueDate", task.getDueDate() == null ? null : task.getDueDate().toString());
    result.put("followUpDate", task.getFollowUpDate() == null ? null : task.getFollowUpDate().toString());
    return result;
  }

  @GET @Path("/native-user/{instance}")
  public List<String> nativeUser(@PathParam("instance") final long instance) {
    return client.newUserTaskSearchRequest().filter(f -> f.processInstanceKey(instance)
        .elementId("user-perform-task")).send().join().items().stream()
        .map(t -> t.getUserTaskKey().toString()).toList();
  }

  @GET @Path("/job/{key}")
  public Map<String, Object> job(@PathParam("key") final long key) {
    final var items = client.newJobSearchRequest().filter(f -> f.jobKey(key)).send().join().items();
    if (items.isEmpty()) return Map.of();
    final var job = items.get(0);
    final var result = new java.util.HashMap<String, Object>();
    result.put("state", job.getState().name());
    result.put("deadline", job.getDeadline() == null ? null : job.getDeadline().toString());
    result.put("retries", job.getRetries());
    result.put("errorMessage", job.getErrorMessage());
    return result;
  }

  @GET @Path("/incident/{key}")
  public List<String> incidents(@PathParam("key") final long key) {
    return client.newIncidentSearchRequest().filter(f -> f.jobKey(key).state(IncidentState.ACTIVE))
        .send().join().items().stream().map(i -> i.getErrorType().name()).toList();
  }

  @GET @Path("/listener")
  public Map<String, Object> listener() {
    final var listener = client.newGlobalTaskListenerGetRequest("process-engine-user-tasks").send().join();
    return Map.of("id", listener.getId(), "type", listener.getType(), "retries", listener.getRetries(),
        "events", listener.getEventTypes().stream().map(Enum::name).toList());
  }

  @POST @Path("/user/{key}/complete")
  public void rawComplete(@PathParam("key") final long key) {
    client.newCompleteUserTaskCommand(key).variables(Map.of("rawCompleted", true)).send().join();
  }

  @POST @Path("/user/{key}/assign")
  public void rawAssign(@PathParam("key") final long key) {
    client.newAssignUserTaskCommand(key).assignee("alice").send().join();
  }

  @POST @Path("/cancel/{key}")
  public void cancel(@PathParam("key") final long key) {
    client.newCancelInstanceCommand(key).send().join();
  }
}
