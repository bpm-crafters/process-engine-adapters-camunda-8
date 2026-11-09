package dev.bpmcrafters.example.javac8quarkus;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Smoke test running the simple process against the Camunda instance provided by the
 * quarkus-camunda dev services, mirroring the manual {@code simple-process-demo.http} flow.
 */
@QuarkusTest
class SimpleProcessQuarkusTest {

  @Inject
  CamundaClient camundaClient;

  @Test
  void runs_simple_process_round_trip() {
    var correlationKey = UUID.randomUUID().toString();

    given()
      .when().post("/simple-service-tasks/deploy")
      .then().statusCode(201);

    var processInstanceId = given()
      .queryParam("value", correlationKey)
      .queryParam("intValue", 1)
      .when().post("/simple-service-tasks/start-process")
      .then().statusCode(201)
      .extract().header("Location");

    assertThat(processInstanceId).isNotBlank();

    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
      var taskIds = given()
        .when().get("/simple-service-tasks/tasks")
        .then().statusCode(200)
        .extract().jsonPath()
        .getList("findAll { it.meta.processInstanceId == '" + processInstanceId + "' }.taskId", String.class);
      assertThat(taskIds).isNotEmpty();
    });

    var taskId = given()
      .when().get("/simple-service-tasks/tasks")
      .then().statusCode(200)
      .extract().jsonPath()
      .getList("findAll { it.meta.processInstanceId == '" + processInstanceId + "' }.taskId", String.class)
      .get(0);

    given()
      .queryParam("value", "value-of-user-task-completion")
      .when().post("/simple-service-tasks/tasks/" + taskId + "/complete")
      .then().statusCode(204);

    // give the process time to reach the message catch event, mirroring the demo script
    await().pollDelay(Duration.ofSeconds(5)).until(() -> true);

    given()
      .queryParam("value", "value-delivered-by-correlation")
      .when().post("/simple-service-tasks/correlate/" + correlationKey)
      .then().statusCode(204);

    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
      var instances = camundaClient.newProcessInstanceSearchRequest()
        .filter(filter -> filter.processInstanceKey(Long.parseLong(processInstanceId)))
        .send().join().items();
      assertThat(instances).isNotEmpty();
      assertThat(instances.get(0).getState()).isEqualTo(ProcessInstanceState.COMPLETED);
    });
  }
}
