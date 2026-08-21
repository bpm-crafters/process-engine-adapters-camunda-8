package dev.bpmcrafters.example.javac8;

import dev.bpmcrafters.example.common.adapter.out.process.UserTaskAdapter;
import dev.bpmcrafters.example.common.adapter.out.process.WorkflowAdapter;
import dev.bpmcrafters.example.common.application.port.in.CorrelateInPort;
import dev.bpmcrafters.example.common.application.port.in.DeployInPort;
import dev.bpmcrafters.example.common.application.port.in.PerformUserTaskInPort;
import dev.bpmcrafters.example.common.application.port.in.SignalInPort;
import dev.bpmcrafters.example.common.application.port.in.StartProcessInstanceInPort;
import dev.bpmcrafters.example.common.application.port.out.UserTaskOutPort;
import dev.bpmcrafters.example.common.application.port.out.WorkflowOutPort;
import dev.bpmcrafters.example.common.spring.SimpleServiceTaskController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring wiring of the framework-free common fixture provided by the
 * {@code java-common-fixture-spring} module. No Camunda connection is required: the beans are
 * created eagerly, task subscriptions happen asynchronously after startup.
 */
@SpringBootTest
class CommonFixtureWiringTest {

  @Autowired
  ApplicationContext context;

  @Test
  void wires_fixture_use_cases_adapters_and_controller() {
    assertThat(context.getBean(WorkflowOutPort.class)).isInstanceOf(WorkflowAdapter.class);
    assertThat(context.getBean(UserTaskOutPort.class)).isInstanceOf(UserTaskAdapter.class);
    assertThat(context.getBean(DeployInPort.class)).isNotNull();
    assertThat(context.getBean(StartProcessInstanceInPort.class)).isNotNull();
    assertThat(context.getBean(PerformUserTaskInPort.class)).isNotNull();
    assertThat(context.getBean(CorrelateInPort.class)).isNotNull();
    assertThat(context.getBean(SignalInPort.class)).isNotNull();
    assertThat(context.getBean(SimpleServiceTaskController.class).getTasks().getBody()).isEmpty();
  }
}
