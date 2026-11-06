package dev.bpmcrafters.example.javac8quarkus.adapter.in.rest;

import dev.bpmcrafters.example.javac8quarkus.adapter.in.process.TaskSubscriptions;
import dev.bpmcrafters.example.javac8quarkus.adapter.out.process.WorkflowAdapter;
import dev.bpmcrafters.processengineapi.task.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Path("/simple-service-tasks")
@Produces(MediaType.APPLICATION_JSON)
public class SimpleServiceTaskResource {
  private final WorkflowAdapter workflow;
  private final TaskSubscriptions tasks;
  private final UserTaskCompletionApi completion;

  public SimpleServiceTaskResource(final WorkflowAdapter workflow, final TaskSubscriptions tasks,
      final UserTaskCompletionApi completion) {
    this.workflow = workflow;
    this.tasks = tasks;
    this.completion = completion;
  }

  @POST @Path("/deploy")
  public Response deploy() {
    return Response.created(URI.create(workflow.deploy())).build();
  }

  @POST @Path("/start-process")
  public Response start(@QueryParam("value") final String value, @QueryParam("intValue") final String intValue) {
    final var text = required("value", value);
    final int number;
    try {
      number = Integer.parseInt(required("intValue", intValue));
    } catch (final NumberFormatException failure) {
      throw new BadRequestException("intValue must be a 32-bit integer", failure);
    }
    return Response.created(URI.create(workflow.start(text, number))).build();
  }

  @POST @Path("/correlate/{correlationKey}")
  public Response correlate(@PathParam("correlationKey") final String key, @QueryParam("value") final String value) {
    workflow.correlate(required("correlationKey", key), required("value", value));
    return Response.noContent().build();
  }

  @POST @Path("/signal")
  public Response signal(@QueryParam("value") final String value) {
    workflow.signal(required("value", value));
    return Response.noContent().build();
  }

  @GET @Path("/tasks")
  public List<TaskInformation> tasks() {
    return tasks.users().getAllTasks();
  }

  @POST @Path("/tasks/{taskId}/complete")
  public Response complete(@PathParam("taskId") final String taskId, @QueryParam("value") final String value) {
    final var text = required("value", value);
    completion.completeTask(new CompleteTaskCmd(taskId, () -> Map.of("some-user-value", text))).join();
    return Response.noContent().build();
  }

  @POST @Path("/tasks/{taskId}/error")
  public Response error(@PathParam("taskId") final String taskId, @QueryParam("value") final String value) {
    required("value", value);
    return Response.status(Response.Status.NOT_IMPLEMENTED)
        .entity(Map.of("error", "BPMN errors are not supported for native user tasks")).build();
  }

  private static String required(final String name, final String value) {
    if (value == null || value.isBlank()) throw new BadRequestException(name + " is required");
    return value;
  }
}
