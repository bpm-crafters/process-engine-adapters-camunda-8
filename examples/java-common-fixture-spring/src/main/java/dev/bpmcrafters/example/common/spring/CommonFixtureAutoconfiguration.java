package dev.bpmcrafters.example.common.spring;

import dev.bpmcrafters.example.common.adapter.out.process.WorkflowAdapter;
import dev.bpmcrafters.example.common.application.port.out.UserTaskOutPort;
import dev.bpmcrafters.example.common.application.port.out.WorkflowOutPort;
import dev.bpmcrafters.example.common.application.usecase.CorrelateUseCase;
import dev.bpmcrafters.example.common.application.usecase.DeployUseCase;
import dev.bpmcrafters.example.common.application.usecase.PerformUserTaskUseCase;
import dev.bpmcrafters.example.common.application.usecase.SignalUseCase;
import dev.bpmcrafters.example.common.application.usecase.StartProcessInstanceUseCase;
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.correlation.SignalApi;
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Spring wiring of the framework-free common fixture: use cases and adapters are declared as beans
 * here, the task handlers and the REST controller are imported.
 */
@Configuration
@Import({TaskHandlerConfiguration.class, SimpleServiceTaskController.class})
@Slf4j
public class CommonFixtureAutoconfiguration {

  @PostConstruct
  public void report() {
    log.info("[EXAMPLE] Started common example fixture actor configuration");
  }

  @Bean
  public WorkflowAdapter workflowAdapter(
      StartProcessApi startProcessApi,
      SignalApi signalApi,
      CorrelationApi correlationApi,
      DeploymentApi deploymentApi
  ) {
    return new WorkflowAdapter(startProcessApi, signalApi, correlationApi, deploymentApi);
  }

  @Bean
  public DeployUseCase deployUseCase(WorkflowOutPort workflowOutPort) {
    return new DeployUseCase(workflowOutPort);
  }

  @Bean
  public StartProcessInstanceUseCase startProcessInstanceUseCase(WorkflowOutPort workflowOutPort) {
    return new StartProcessInstanceUseCase(workflowOutPort);
  }

  @Bean
  public PerformUserTaskUseCase performUserTaskUseCase(UserTaskOutPort userTaskOutPort) {
    return new PerformUserTaskUseCase(userTaskOutPort);
  }

  @Bean
  public CorrelateUseCase correlateUseCase(WorkflowOutPort workflowOutPort) {
    return new CorrelateUseCase(workflowOutPort);
  }

  @Bean
  public SignalUseCase signalUseCase(WorkflowOutPort workflowOutPort) {
    return new SignalUseCase(workflowOutPort);
  }
}
