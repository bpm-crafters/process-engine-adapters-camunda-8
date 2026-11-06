package dev.bpmcrafters.example.javac8quarkus;

import io.camunda.client.CamundaClient;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class CamundaTestResource implements QuarkusTestResourceLifecycleManager {
  private GenericContainer<?> camunda;
  private CamundaClient observer;

  @Override
  public Map<String, String> start() {
    final var version = Objects.requireNonNull(System.getProperty("camunda.version"), "Missing Camunda version");
    camunda = new GenericContainer<>(DockerImageName.parse("camunda/camunda:" + version))
        .withExposedPorts(8080, 26500)
        .withEnv("SPRING_PROFILES_ACTIVE", "broker,consolidated-auth,security")
        .withEnv("CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI", "true")
        .withEnv("CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED", "false")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_TYPE", "rdbms")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL", "jdbc:h2:mem:example;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME", "sa")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD", "")
        .withEnv("ZEEBE_BROKER_EXPORTERS_RDBMS_CLASSNAME", "io.camunda.exporter.rdbms.RdbmsExporter")
        .waitingFor(Wait.forHttp("/v2/topology").forPort(8080)
            .forResponsePredicate(body -> body.contains("\"healthy\"") && body.contains("\"leader\"")))
        .withStartupTimeout(Duration.ofMinutes(3));
    return startClientAndServer();
  }

  private Map<String, String> startClientAndServer() {
    try {
      camunda.start();
      observer = CamundaClient.newClientBuilder().applyEnvironmentVariableOverrides(false)
          .grpcAddress(URI.create(address(26500))).restAddress(URI.create(address(8080)))
          .preferRestOverGrpc(true).defaultRequestTimeout(Duration.ofSeconds(10)).build();
      return Map.of("quarkus.camunda.client.broker.gateway-address", address(26500),
          "quarkus.camunda.client.broker.rest-address", address(8080));
    } catch (final RuntimeException failure) {
      try {
        failure.addSuppressed(new IllegalStateException("Camunda logs:\n" + camunda.getLogs()));
      } catch (final RuntimeException diagnosticFailure) {
        failure.addSuppressed(diagnosticFailure);
      }
      stop();
      throw failure;
    }
  }

  private String address(final int port) {
    return "http://" + camunda.getHost() + ":" + camunda.getMappedPort(port);
  }

  @Override
  public void inject(final TestInjector injector) {
    injector.injectIntoFields(observer, new TestInjector.MatchesType(CamundaClient.class));
  }

  @Override
  public void stop() {
    try {
      if (observer != null) observer.close();
    } finally {
      if (camunda != null) camunda.close();
    }
  }
}
