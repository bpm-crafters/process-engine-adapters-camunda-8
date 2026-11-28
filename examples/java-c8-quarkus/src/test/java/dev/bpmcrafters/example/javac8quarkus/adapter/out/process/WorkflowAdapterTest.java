package dev.bpmcrafters.example.javac8quarkus.adapter.out.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.bpmcrafters.example.javac8quarkus.adapter.shared.ComplexVariable;
import dev.bpmcrafters.processengineapi.Empty;
import dev.bpmcrafters.processengineapi.correlation.*;
import dev.bpmcrafters.processengineapi.deploy.*;
import dev.bpmcrafters.processengineapi.process.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowAdapterTest {
  private final DeploymentApi deployments = mock(DeploymentApi.class);
  private final StartProcessApi processes = mock(StartProcessApi.class);
  private final CorrelationApi messages = mock(CorrelationApi.class);
  private final SignalApi signals = mock(SignalApi.class);
  private final WorkflowAdapter adapter = new WorkflowAdapter(deployments, processes, messages, signals);

  @Test
  void deploysOnlyTheSharedClasspathModel() throws Exception {
    when(deployments.deploy(any())).thenReturn(CompletableFuture.completedFuture(new DeploymentInformation("456", null, null)));
    assertThat(adapter.deploy()).isEqualTo("456");
    final var command = ArgumentCaptor.forClass(DeployBundleCommand.class);
    verify(deployments).deploy(command.capture());
    assertThat(command.getValue().getTenantId()).isNull();
    assertThat(command.getValue().getResources()).hasSize(1);
    final var resource = command.getValue().getResources().get(0);
    assertThat(resource.getName()).isEqualTo("simple-process.bpmn");
    try (final var stream = resource.getResourceStream()) {
      assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).contains("simple-process-1");
    }
  }

  @Test
  void startsWithAllSixTypedVariables() {
    when(processes.startProcess(any())).thenReturn(CompletableFuture.completedFuture(new ProcessInformation("789", Map.of())));
    assertThat(adapter.start("unique", 123)).isEqualTo("789");
    final var command = ArgumentCaptor.forClass(StartProcessByDefinitionCmd.class);
    verify(processes).startProcess(command.capture());
    assertThat(command.getValue().getDefinitionKey()).isEqualTo("simple-process-1");
    assertThat(command.getValue().getRestrictions()).isEmpty();
    assertThat(command.getValue().get()).isEqualTo(Map.of("stringValue", "unique", "intValue", 123,
        "listVariable", List.of("element1", "element2"), "listIntVariable", List.of(12, 13, 15L),
        "toBeOrNotToBe", true, "complex", new ComplexVariable("foo", "bar")));
  }

  @Test
  void correlatesByKeyAndBroadcastsTheModeledSignal() {
    when(messages.correlateMessage(any())).thenReturn(CompletableFuture.completedFuture(Empty.INSTANCE));
    when(signals.sendSignal(any())).thenReturn(CompletableFuture.completedFuture(Empty.INSTANCE));
    adapter.correlate("unique", "message-value");
    adapter.signal("signal-value");
    final var message = ArgumentCaptor.forClass(CorrelateMessageCmd.class);
    verify(messages).correlateMessage(message.capture());
    assertThat(message.getValue().getMessageName()).isEqualTo("message1");
    assertThat(message.getValue().getCorrelation().get().getCorrelationKey()).isEqualTo("unique");
    assertThat(message.getValue().get()).isEqualTo(Map.of("message-delivered-value", "message-value"));
    assertThat(message.getValue().getRestrictions()).isEmpty();
    final var signal = ArgumentCaptor.forClass(SendSignalCmd.class);
    verify(signals).sendSignal(signal.capture());
    assertThat(signal.getValue().getSignalName()).isEqualTo("signal1");
    assertThat(signal.getValue().get()).isEqualTo(Map.of("signal-delivered-value", "signal-value"));
    assertThat(signal.getValue().getRestrictions()).isEmpty();
  }
}
