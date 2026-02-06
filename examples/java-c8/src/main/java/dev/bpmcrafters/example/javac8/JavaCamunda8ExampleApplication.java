package dev.bpmcrafters.example.javac8;

import dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties;
import io.camunda.client.spring.properties.CamundaClientProperties;
import io.camunda.tasklist.CamundaTaskListClient;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import static dev.bpmcrafters.processengineapi.adapter.c8.springboot.C8AdapterProperties.DEFAULT_PREFIX;

@SpringBootApplication
@Slf4j
public class JavaCamunda8ExampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(JavaCamunda8ExampleApplication.class, args);
  }

  @Bean
  @ConditionalOnProperty(prefix = DEFAULT_PREFIX, name = "user-tasks.completion-strategy", havingValue = "tasklist")
  public CamundaTaskListClient myCamundaTaskListClient(
    CamundaClientProperties clientProperties,
    C8AdapterProperties c8AdapterProperties
  ) throws Exception {

    var builder = CamundaTaskListClient.builder()
      .taskListUrl(c8AdapterProperties.getUserTasks().getTasklistUrl())
      .shouldReturnVariables()
    ;

    switch (clientProperties.getMode()) {
      case saas ->
        builder = builder.saaSAuthentication(
          clientProperties.getAuth().getClientId(),
          clientProperties.getAuth().getClientSecret()
        );

      case selfManaged ->
        builder = builder.selfManagedAuthentication(
          clientProperties.getAuth().getClientId(),
          clientProperties.getAuth().getClientSecret(),
          clientProperties.getAuth().getTokenUrl().toASCIIString()
        );
    }

    return builder.build();
  }

}
