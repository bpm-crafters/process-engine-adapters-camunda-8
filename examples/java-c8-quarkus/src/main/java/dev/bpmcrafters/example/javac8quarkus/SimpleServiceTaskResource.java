package dev.bpmcrafters.example.javac8quarkus;

import dev.bpmcrafters.example.common.application.port.in.CorrelateInPort;
import dev.bpmcrafters.example.common.application.port.in.DeployInPort;
import dev.bpmcrafters.example.common.application.port.in.PerformUserTaskInPort;
import dev.bpmcrafters.example.common.application.port.in.SignalInPort;
import dev.bpmcrafters.example.common.application.port.in.StartProcessInstanceInPort;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * JAX-RS counterpart of the fixture's Spring MVC {@code SimpleServiceTaskController}, serving the
 * same endpoints, so the {@code simple-process-demo.http} script works unchanged.
 */
@Path("/simple-service-tasks")
@Produces(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
@Slf4j
public class SimpleServiceTaskResource {

  private final DeployInPort deployPort;
  private final StartProcessInstanceInPort processInstancePort;
  private final PerformUserTaskInPort taskPort;
  private final CorrelateInPort correlatePort;
  private final SignalInPort signalPort;

  @POST
  @Path("/deploy")
  @SneakyThrows
  public Response deploy() {
    val info = deployPort.deploy().get();
    log.info("Deployed process {}", info.getDeploymentKey());
    return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, info.getDeploymentKey()).build();
  }

  @POST
  @Path("/start-process")
  @SneakyThrows
  public Response startUseCase(@QueryParam("value") String value, @QueryParam("intValue") Integer intValue) {
    val processInstanceId = processInstancePort.startNew(value, intValue).get();
    log.info("Started process instance {}", processInstanceId);
    return Response.status(Response.Status.CREATED).header(HttpHeaders.LOCATION, processInstanceId).build();
  }

  @POST
  @Path("/correlate/{correlationKey}")
  @SneakyThrows
  public Response correlateMessage(@PathParam("correlationKey") String correlationKey, @QueryParam("value") String value) {
    log.info("Correlated message using correlation key {}", correlationKey);
    correlatePort.correlateMessage(correlationKey, value).get();
    return Response.noContent().build();
  }

  @POST
  @Path("/signal")
  @SneakyThrows
  public Response sendSignal(@QueryParam("value") String value) {
    log.info("Sending signal");
    signalPort.deliverSignal(value).get();
    return Response.noContent().build();
  }

  @GET
  @Path("/tasks")
  @SneakyThrows
  public Response getTasks() {
    return Response.ok(taskPort.getUserTasks().get()).build();
  }

  @POST
  @Path("/tasks/{taskId}/complete")
  @SneakyThrows
  public Response complete(@PathParam("taskId") String taskId, @QueryParam("value") String value) {
    taskPort.complete(taskId, value).get();
    return Response.noContent().build();
  }

  @POST
  @Path("/tasks/{taskId}/error")
  @SneakyThrows
  public Response error(@PathParam("taskId") String taskId, @QueryParam("value") String value) {
    taskPort.completeWithError(taskId, value).get();
    return Response.noContent().build();
  }
}
