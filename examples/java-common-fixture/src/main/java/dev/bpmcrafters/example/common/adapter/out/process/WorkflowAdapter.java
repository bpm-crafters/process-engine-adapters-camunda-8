package dev.bpmcrafters.example.common.adapter.out.process;

import dev.bpmcrafters.example.common.adapter.shared.SimpleProcessWorkflowConst;
import dev.bpmcrafters.example.common.adapter.shared.SimpleProcessWorkflowConst.Expressions;
import dev.bpmcrafters.example.common.application.port.out.WorkflowOutPort;
import dev.bpmcrafters.processengineapi.correlation.*;
import dev.bpmcrafters.processengineapi.deploy.DeployBundleCommand;
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.deploy.DeploymentInformation;
import dev.bpmcrafters.processengineapi.deploy.NamedResource;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.process.StartProcessByDefinitionCmd;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WorkflowAdapter implements WorkflowOutPort {

  private final StartProcessApi startProcessApi;
  private final SignalApi signalApi;
  private final CorrelationApi correlationApi;
  private final DeploymentApi deploymentApi;

  @Override
  @SneakyThrows
  public DeploymentInformation deploySimpleProcess() {
    return deploymentApi.deploy(
      new DeployBundleCommand(
        List.of(
          NamedResource.fromClasspath(SimpleProcessWorkflowConst.BPMN)
        ),
        null
      )
    ).get();
  }

  @Override
  @SneakyThrows
  public String startSimpleProcess(String value, Integer intValue) {
    return startProcessApi.startProcess(
        new StartProcessByDefinitionCmd(
          SimpleProcessWorkflowConst.KEY,
          () -> Map.of(
            "stringValue", value,
            "intValue", intValue,
            "listVariable", List.of("element1", "element2")
          ),
          Map.of()
        )
      ).get()
      .getInstanceId();
  }

  @Override
  @SneakyThrows
  public void correlateMessage(String correlationValue, String variableValue) {
    correlationApi.correlateMessage(
      new CorrelateMessageCmd(
        Expressions.MESSAGE_1,
        () -> Map.of(
          "message-delivered-value", variableValue
        ),
        () -> Correlation.withKey(correlationValue), // C8 supports correlation by key
        Map.of()
      )
    ).get();
  }

  @Override
  @SneakyThrows
  public void deliverSignal(String variableValue) {
    signalApi.sendSignal(
      new SendSignalCmd(
        Expressions.SIGNAL_1,
        () -> Map.of(
          "signal-delivered-value", variableValue
        ),
        Map.of()
      )
    ).get();
  }
}
