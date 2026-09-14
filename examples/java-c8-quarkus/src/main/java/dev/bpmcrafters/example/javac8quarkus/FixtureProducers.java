package dev.bpmcrafters.example.javac8quarkus;

import dev.bpmcrafters.example.common.adapter.out.process.UserTaskAdapter;
import dev.bpmcrafters.example.common.adapter.out.process.WorkflowAdapter;
import dev.bpmcrafters.example.common.application.port.in.CorrelateInPort;
import dev.bpmcrafters.example.common.application.port.in.DeployInPort;
import dev.bpmcrafters.example.common.application.port.in.PerformUserTaskInPort;
import dev.bpmcrafters.example.common.application.port.in.SignalInPort;
import dev.bpmcrafters.example.common.application.port.in.StartProcessInstanceInPort;
import dev.bpmcrafters.example.common.application.port.out.UserTaskOutPort;
import dev.bpmcrafters.example.common.application.port.out.WorkflowOutPort;
import dev.bpmcrafters.example.common.application.usecase.CorrelateUseCase;
import dev.bpmcrafters.example.common.application.usecase.DeployUseCase;
import dev.bpmcrafters.example.common.application.usecase.PerformUserTaskUseCase;
import dev.bpmcrafters.example.common.application.usecase.SignalUseCase;
import dev.bpmcrafters.example.common.application.usecase.StartProcessInstanceUseCase;
import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.correlation.SignalApi;
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.support.UserTaskSupport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * CDI wiring of the common fixture, the counterpart of the Spring auto-configuration of the
 * fixture. The fixture classes are plain constructor-injected POJOs, so they can be produced
 * for CDI directly.
 */
@Singleton
public class FixtureProducers {

  @Produces
  @ApplicationScoped
  public WorkflowOutPort workflowAdapter(
      StartProcessApi startProcessApi,
      SignalApi signalApi,
      CorrelationApi correlationApi,
      DeploymentApi deploymentApi
  ) {
    return new WorkflowAdapter(startProcessApi, signalApi, correlationApi, deploymentApi);
  }

  // UserTaskSupport is a final class, so it is produced without a client proxy as singleton.
  @Produces
  @Singleton
  public UserTaskSupport userTaskSupport(TaskSubscriptionApi taskSubscriptionApi) {
    var support = new UserTaskSupport();
    support.subscribe(taskSubscriptionApi, CommonRestrictions.builder().build(), null, null);
    return support;
  }

  @Produces
  @ApplicationScoped
  public UserTaskOutPort userTaskAdapter(UserTaskSupport userTaskSupport, UserTaskCompletionApi userTaskCompletionApi) {
    return new UserTaskAdapter(userTaskSupport, userTaskCompletionApi);
  }

  @Produces
  @ApplicationScoped
  public DeployInPort deployUseCase(WorkflowOutPort workflowOutPort) {
    return new DeployUseCase(workflowOutPort);
  }

  @Produces
  @ApplicationScoped
  public StartProcessInstanceInPort startProcessInstanceUseCase(WorkflowOutPort workflowOutPort) {
    return new StartProcessInstanceUseCase(workflowOutPort);
  }

  @Produces
  @ApplicationScoped
  public PerformUserTaskInPort performUserTaskUseCase(UserTaskOutPort userTaskOutPort) {
    return new PerformUserTaskUseCase(userTaskOutPort);
  }

  @Produces
  @ApplicationScoped
  public CorrelateInPort correlateUseCase(WorkflowOutPort workflowOutPort) {
    return new CorrelateUseCase(workflowOutPort);
  }

  @Produces
  @ApplicationScoped
  public SignalInPort signalUseCase(WorkflowOutPort workflowOutPort) {
    return new SignalUseCase(workflowOutPort);
  }
}
