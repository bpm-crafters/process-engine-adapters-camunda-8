package dev.bpmcrafters.example.javac8quarkus.adapter.out.process;

import dev.bpmcrafters.example.javac8quarkus.adapter.shared.ComplexVariable;
import dev.bpmcrafters.processengineapi.correlation.*;
import dev.bpmcrafters.processengineapi.deploy.*;
import dev.bpmcrafters.processengineapi.process.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class WorkflowAdapter {
  private final DeploymentApi deployments;
  private final StartProcessApi processes;
  private final CorrelationApi messages;
  private final SignalApi signals;

  public WorkflowAdapter(final DeploymentApi deployments, final StartProcessApi processes,
      final CorrelationApi messages, final SignalApi signals) {
    this.deployments = deployments;
    this.processes = processes;
    this.messages = messages;
    this.signals = signals;
  }

  public String deploy() {
    return deployments.deploy(new DeployBundleCommand(
        List.of(NamedResource.fromClasspath("simple-process.bpmn")), null)).join().getDeploymentKey();
  }

  public String start(final String value, final int intValue) {
    final var payload = Map.<String, Object>of(
        "stringValue", value, "intValue", intValue,
        "listVariable", List.of("element1", "element2"),
        "listIntVariable", List.of(12, 13, 15L), "toBeOrNotToBe", true,
        "complex", new ComplexVariable("foo", "bar"));
    return processes.startProcess(new StartProcessByDefinitionCmd(
        "simple-process-1", () -> payload, Map.of())).join().getInstanceId();
  }

  public void correlate(final String key, final String value) {
    messages.correlateMessage(new CorrelateMessageCmd("message1",
        () -> Map.of("message-delivered-value", value), () -> Correlation.withKey(key), Map.of())).join();
  }

  public void signal(final String value) {
    signals.sendSignal(new SendSignalCmd("signal1",
        () -> Map.of("signal-delivered-value", value), Map.of())).join();
  }
}
