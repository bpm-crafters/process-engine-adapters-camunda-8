---
title: Camunda Platform 8 with Quarkus
---

If you build your process application with Quarkus, use the Quarkus adapter library. It wires the framework-free
`c8-core` adapter into CDI, analogous to what the Spring Boot starter does for Spring.

First add the adapter and the [Quarkiverse Camunda extension](https://github.com/quarkiverse/quarkus-camunda) to your
project's classpath:

```xml
<dependencies>
  <dependency>
    <groupId>dev.bpm-crafters.process-engine-adapters</groupId>
    <artifactId>process-engine-adapter-camunda-platform-c8-quarkus</artifactId>
  </dependency>

  <!-- Provides the CamundaClient CDI bean, Camunda dev services, health checks and metrics. -->
  <dependency>
    <groupId>io.quarkiverse.camunda</groupId>
    <artifactId>quarkus-camunda</artifactId>
  </dependency>

  <!-- Required at runtime by the scheduled user task delivery and decision evaluation. -->
  <dependency>
    <groupId>com.fasterxml.jackson.module</groupId>
    <artifactId>jackson-module-kotlin</artifactId>
  </dependency>
</dependencies>
```

and configure the adapter in your `application.properties`:

```properties
dev.bpm-crafters.process-api.adapter.c8.enabled=true
dev.bpm-crafters.process-api.adapter.c8.service-tasks.delivery-strategy=SUBSCRIPTION
dev.bpm-crafters.process-api.adapter.c8.service-tasks.worker-id=my-worker
dev.bpm-crafters.process-api.adapter.c8.user-tasks.delivery-strategy=SCHEDULED
dev.bpm-crafters.process-api.adapter.c8.user-tasks.schedule-delivery-fixed-rate-in-seconds=5
```

The property tree is identical to the Spring Boot starter, see the [reference](reference-c8.md) for all properties,
delivery strategies and defaults.

## Provided beans

With the adapter enabled, the following beans are injectable: `StartProcessApi`, `TaskSubscriptionApi`,
`CorrelationApi`, `SignalApi`, `DeploymentApi`, `EvaluateDecisionApi`, `ServiceTaskCompletionApi`,
`UserTaskCompletionApi`, `UserTaskModificationApi`, `SubscriptionRepository` and `FailureRetrySupplier`.
Beans marked as default beans (`SubscriptionRepository`, `EvaluateDecisionApi`, `FailureRetrySupplier` and the
completion/modification apis) can be replaced by providing an own bean of the same type.

All beans are lazy. If the adapter is disabled (`enabled` is `false` or not set), the application still starts, but
using one of the adapter beans fails with a message naming the `enabled` property. The three properties that are
required by the Spring Boot starter (`service-tasks.delivery-strategy`, `service-tasks.worker-id`,
`user-tasks.delivery-strategy`) are validated on startup as soon as the adapter is enabled — a missing key aborts the
startup like Spring's configuration binding error. Without any `CamundaClient` bean on the classpath the application
still builds; using the adapter then fails with a message pointing to the quarkus-camunda extension.

## Task handler registration

The adapter subscribes its task deliveries in a `StartupEvent` observer running *after* observers with default
priority. Register your task subscriptions in an own startup observer, the equivalent of registering handlers during
bean initialization with Spring:

```java
@Singleton
public class TaskHandlerRegistration {

  private final MyTaskHandler handler;

  TaskHandlerRegistration(TaskSubscriptionApi subscriptionApi, ServiceTaskCompletionApi completionApi) {
    this.handler = new MyTaskHandler(subscriptionApi, completionApi);
  }

  void onStart(@Observes StartupEvent event) {
    handler.register();
  }

  void onStop(@Observes ShutdownEvent event) {
    handler.unregister();
  }
}
```

Note: with the `SUBSCRIPTION` service task delivery strategy, job workers are opened once when the adapter starts.
Subscriptions registered later (e.g. at runtime) are not picked up — the same behavior as with the Spring Boot
starter. The `SCHEDULED` user task delivery re-reads subscriptions on every refresh, so late subscriptions are fine
there.

For the `SCHEDULED` and `SUBSCRIPTION_REFRESHING` user task delivery strategies, the adapter runs a small dedicated
scheduler (thread names `C8REMOTE-SCHEDULER-*`), so no quarkus-scheduler extension is required.

## Camunda client configuration

The `CamundaClient` is configured entirely through the quarkus-camunda extension (`quarkus.camunda.*` properties):

```properties
# self-managed
quarkus.camunda.client.broker.gateway-address=http://localhost:26500
quarkus.camunda.client.broker.rest-address=http://localhost:8080
# SaaS: quarkus.camunda.client.cloud.cluster-id / client-id / client-secret / region
# OAuth self-managed: quarkus.camunda.client.oauth.*
```

Things to be aware of:

- In dev and test mode, the extension's dev services start a Camunda container automatically when no broker address is
  configured — no docker-compose needed for the development loop.
- quarkus-camunda supports OAuth and SaaS credentials, but no basic auth. For a basic-auth-protected cluster, register
  a CDI bean implementing `io.quarkiverse.camunda.ClientInterceptor` (grpc) and/or an Apache
  `org.apache.hc.client5.http.async.AsyncExecChainHandler` (rest, the default transport) adding the
  `Authorization: Basic ...` header — the extension wires both bean types into the client builder. Or disable security
  locally (like Camunda 8 Run with unprotected api).
- `quarkus.camunda.active=false` replaces the client with a no-op implementation. The adapter would then run against
  that no-op client — disable the adapter as well in this case.
- The extension's own `@JobWorker` annotation is a competing worker abstraction over the same client. It is inactive
  as long as you do not annotate methods with it; the adapter's task subscriptions are unaffected.

## Version notes

The adapter library compiles against the same `io.camunda:camunda-client-java` version as this project
(see the compatibility table in the README) and is tested against the quarkus-camunda version pinned in the BOM.
Only long-stable Quarkus apis (`StartupEvent`, `@ConfigMapping`, `@DefaultBean`) are used, so running on the current
Quarkus LTS is expected to work, while the pinned combination is the verified one.

Native image compilation is expected to work through the quarkus-camunda extension (which registers the client for
reflection); payload classes serialized to process variables must be registered for reflection by the application
(`@RegisterForReflection`). Native mode is not part of this project's verification yet.
