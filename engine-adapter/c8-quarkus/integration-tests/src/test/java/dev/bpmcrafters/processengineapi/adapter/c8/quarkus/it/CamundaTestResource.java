package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.it;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class CamundaTestResource implements QuarkusTestResourceLifecycleManager {

  private GenericContainer<?> camunda;

  @Override
  public Map<String, String> start() {
    camunda = createContainer();
    try {
      camunda.start();
      return Map.of(
          "quarkus.camunda.client.broker.gateway-address", address(26500),
          "quarkus.camunda.client.broker.rest-address", address(8080));
    } catch (final RuntimeException failure) {
      throw startupFailure(failure);
    }
  }

  private IllegalStateException startupFailure(final RuntimeException failure) {
    final var diagnostic = new IllegalStateException("Camunda integration test container failed to start", failure);
    try {
      diagnostic.addSuppressed(new IllegalStateException("Camunda container logs:\n" + camunda.getLogs()));
    } catch (final RuntimeException logFailure) {
      diagnostic.addSuppressed(logFailure);
    }
    try {
      camunda.close();
    } catch (final RuntimeException cleanupFailure) {
      diagnostic.addSuppressed(cleanupFailure);
    }
    return diagnostic;
  }

  private GenericContainer<?> createContainer() {
    final var version = Objects.requireNonNull(System.getProperty("camunda.version"), "Missing Camunda version");
    return new GenericContainer<>(DockerImageName.parse("camunda/camunda:" + version))
        .withExposedPorts(8080, 26500)
        .withEnv("SPRING_PROFILES_ACTIVE", "broker,consolidated-auth,security")
        .withEnv("CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI", "true")
        .withEnv("CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED", "false")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_TYPE", "rdbms")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL", "jdbc:h2:mem:quarkus;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME", "sa")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD", "")
        .withEnv("ZEEBE_BROKER_EXPORTERS_RDBMS_CLASSNAME", "io.camunda.exporter.rdbms.RdbmsExporter")
        .waitingFor(Wait.forHttp("/v2/topology").forPort(8080)
            .forResponsePredicate(body -> body.contains("\"healthy\"") && body.contains("\"leader\"")))
        .withStartupTimeout(Duration.ofMinutes(3));
  }

  private String address(final int port) {
    return "http://" + camunda.getHost() + ":" + camunda.getMappedPort(port);
  }

  @Override
  public void stop() {
    if (camunda != null) {
      camunda.close();
    }
  }
}
