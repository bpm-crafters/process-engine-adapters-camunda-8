# Quarkus adapter

Use this extension to inject the Process Engine API into a Quarkus application and run it on the JVM.

## Versions and prerequisites

| Component | Version / requirement |
| --- | --- |
| Adapter | `2026.09.2-SNAPSHOT` (**unreleased**, not a Maven Central release) |
| Quarkus platform | Minimum `3.37.3`; default `3.40.1.1` |
| Camunda server | Match the SDK; default test server `8.10.7` |
| Camunda Java client | Official SDK `8.10.7`, direct runtime dependency |
| Application JDK | Minimum Java 17; JVM-only |
| Full repository build JDK | Java 17 |
| Core and Spring artifacts | Continue to target Java 17 |
| Integration tests | Docker |

Build/install the development artifacts locally or use your configured snapshot repository before consuming this unreleased version.
The Quarkus dependency management stays inside `engine-adapter/c8-quarkus` and `examples/java-c8-quarkus`; the core does not depend on Quarkus or Spring.

For a runnable Java application with HTTP walkthrough, local Compose stack, SaaS profile, and automatic workers,
see [java-c8-quarkus](../examples/java-c8-quarkus/README.md). It reuses the Spring Boot 3 example's process model
and feature range. The extension integration application below exercises broader API and delivery-strategy coverage.

The extension integrates the [official Camunda Java client](https://docs.camunda.io/docs/apis-tools/java-client/getting-started/)
directly. It does not require Quarkiverse Camunda. Repository dependency management keeps the SDK at `8.10.7`.
Use matching SDK/server versions and one SDK family per classpath. Matching `8.8`/`8.9` SDK integration
is not implemented or verified; using the `8.10.7` SDK against older servers is not a supported compatibility recipe.
Minimum framework/JDK versions describe the target; lower-platform compatibility validation remains pending.
Server container JVM requirements are independent of the application's Java 17 minimum.

## Install

Import the Quarkus platform BOM and adapter BOM in your application's `pom.xml`. Use a standard
[Quarkus Maven application](https://quarkus.io/guides/maven-tooling) with the Quarkus Maven plugin configured.

```xml
<properties>
  <quarkus.platform.version>3.40.1.1</quarkus.platform.version>
  <process-engine-adapter-camunda-platform-c8.version>2026.09.2-SNAPSHOT</process-engine-adapter-camunda-platform-c8.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.quarkus.platform</groupId>
      <artifactId>quarkus-bom</artifactId>
      <version>${quarkus.platform.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>dev.bpm-crafters.process-engine-adapters</groupId>
      <artifactId>process-engine-adapter-camunda-platform-c8-bom</artifactId>
      <version>${process-engine-adapter-camunda-platform-c8.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>dev.bpm-crafters.process-engine-adapters</groupId>
    <artifactId>process-engine-adapter-camunda-platform-c8-quarkus</artifactId>
  </dependency>
</dependencies>
```

Add only the runtime artifact. Quarkus automatically resolves
`process-engine-adapter-camunda-platform-c8-quarkus-deployment` during augmentation.
Both artifacts appear in the adapter BOM. You do not need a separate Quarkiverse Camunda dependency.

## Connect and enable

Add the following to `src/main/resources/application.properties` for an explicitly managed local server:

```properties
quarkus.process-engine-api.c8.enabled=true
quarkus.process-engine-api.c8.service-tasks.delivery-strategy=SUBSCRIPTION
quarkus.process-engine-api.c8.service-tasks.worker-id=my-application
quarkus.process-engine-api.c8.user-tasks.delivery-strategy=SCHEDULED

quarkus.process-engine-api.c8.client.grpc-address=http://localhost:26500
quarkus.process-engine-api.c8.client.rest-address=http://localhost:8080
```

The adapter owns `quarkus.process-engine-api.c8.*`. Use both endpoints for the same cluster.
The REST address is the base URL without `/v2`; SaaS base URLs may include a cluster path.
Do not copy credentials into source control. See the [SaaS example](../examples/java-c8-quarkus/README.md#saas).

### Default client and migration

The enabled adapter supplies one `@Singleton @DefaultBean CamundaClient`. An ordinary application
`@Produces @Singleton CamundaClient` replaces it without `@Alternative` or priority. The disabled adapter
does not create a client. Only the default producer validates and uses the following settings:

| Property under `quarkus.process-engine-api.c8.client.` | Default | Meaning |
| --- | --- | --- |
| `grpc-address` | `http://localhost:26500` | HTTP(S) gRPC endpoint |
| `rest-address` | `http://localhost:8080` | HTTP(S) REST base URL |
| `prefer-rest-over-grpc` | `true` | Prefer REST for commands supporting both transports; accepts case-insensitive `true`/`false`, `yes`/`no`, `y`/`n`, `on`/`off`, or `1`/`0` |
| `request-timeout` | `PT45S` | Positive Quarkus duration, for example `PT45S`, `45s`, or `45` (seconds) |
| `oauth.client-id` | None | Nonblank OAuth client ID, paired with secret |
| `oauth.client-secret` | None | Nonblank OAuth secret, paired with ID |
| `oauth.auth-url` | `https://login.cloud.camunda.io/oauth/token` | Token endpoint |
| `oauth.token-audience` | None | Explicit nonblank audience required with OAuth |

Without OAuth credentials the default client uses no authentication. Partial or blank OAuth credentials
fail explicitly. SDK and OAuth environment overrides are disabled so Quarkus configuration takes precedence;
use explicit `${ENV_VAR}` placeholders, as the example does. The SDK's default JSON mapper handles serialization.
Provide an application client for basic authentication, custom TLS, advanced SDK options, or custom serialization.
An override changes CDI wiring, not dependency resolution: it cannot select an older SDK or repair ABI mismatches.
The default producer parses and validates its connection settings only when selected, so malformed or empty
unused client settings do not prevent an application-supplied client from starting.

| Previous Quarkiverse key | Replacement |
| --- | --- |
| `quarkus.camunda.client.broker.gateway-address` | `quarkus.process-engine-api.c8.client.grpc-address` |
| `quarkus.camunda.client.broker.rest-address` | `quarkus.process-engine-api.c8.client.rest-address` |
| `quarkus.camunda.client.oauth.*` | `quarkus.process-engine-api.c8.client.oauth.*` for the four fields above |
| `quarkus.camunda.active`, `resources.enabled`, `devservices.enabled` | Remove; no equivalent is needed |

This extension does not supply Quarkiverse NoOp clients, Dev Services, automatic resource deployment,
annotation workers, health integration, or native-image conveniences. Use explicit `DeploymentApi`
deployment, subscriptions, and an externally managed server or the example's Docker Compose stack.

### Build-time configuration

These properties are fixed at build time. Rebuild the JVM application when you change them.

| Property under `quarkus.process-engine-api.c8.` | Default | Requirement |
| --- | --- | --- |
| `enabled` | `false` | Set to `true` to install adapter beans and lifecycle bindings |
| `service-tasks.delivery-strategy` | None | Required when enabled: `SUBSCRIPTION` or `CUSTOM` |
| `user-tasks.delivery-strategy` | None | Required when enabled: `SCHEDULED`, `SUBSCRIPTION_REFRESHING`, `LISTENER`, or `CUSTOM` |

### Runtime configuration

All remaining adapter settings are runtime configuration. Durations below use seconds.

| Property under `quarkus.process-engine-api.c8.` | Default | Meaning |
| --- | --- | --- |
| `service-tasks.worker-id` | None | Required when enabled, including `CUSTOM` |
| `service-tasks.retries` | `3` | Service-task failure retry count |
| `service-tasks.retry-timeout-in-seconds` | `5` | Service-task retry delay |
| `service-tasks.lock-time-in-seconds` | `300` | Service worker lock duration |
| `user-tasks.schedule-delivery-fixed-rate-in-seconds` | `5` | User-task delivery/refresh interval |

Listener settings use the prefix `quarkus.process-engine-api.c8.user-tasks.listener.`:

| Property | Default |
| --- | --- |
| `topic` | `process-engine-user-tasks` |
| `worker-id` | `process-engine-user-tasks-worker` |
| `max-jobs-active` | `32` |
| `stream-enabled` | `true` |
| `lock-time-in-seconds` | `300` |
| `retry-timeout-in-seconds` | `5` |
| `auto-register-global-listener` | `false` |
| `global-listener-id` | `process-engine-user-tasks` |
| `global-listener-retries` | `3` |
| `global-listener-after-non-global` | `true` |
| `global-listener-priority` | `0` |
| `preload-existing-tasks` | `true` |

## APIs and delivery strategies

Inject the API interfaces with CDI. With the adapter enabled, the extension supplies defaults for:

| API | Purpose |
| --- | --- |
| `DeploymentApi` | Deploy process and decision resources |
| `StartProcessApi` | Start process instances |
| `CorrelationApi` | Correlate messages |
| `SignalApi` | Broadcast signals |
| `EvaluateDecisionApi` | Evaluate decisions |
| `TaskSubscriptionApi` | Register task handlers |
| `ServiceTaskCompletionApi` | Complete, fail, or report service-task errors |
| `UserTaskCompletionApi` | Complete user tasks, except in user-task `CUSTOM` mode |
| `UserTaskModificationApi` | Available in every mode; modifies native user tasks, not legacy job-based user-task keys |

The APIs retain the core adapter's supported restrictions and explicit unsupported-command errors.
Injection does not mean that every command variant works for every task type.

| Service-task strategy | Behavior |
| --- | --- |
| `SUBSCRIPTION` | Open workers for registered external-task subscriptions at startup |
| `CUSTOM` | Do not open built-in service delivery workers; retain service completion and retry support |

| User-task strategy | Behavior |
| --- | --- |
| `SCHEDULED` | Poll and deliver native Camunda user tasks; supply native user-task completion |
| `SUBSCRIPTION_REFRESHING` | Deliver legacy job-based user tasks and refresh delivery state; supply job completion |
| `LISTENER` | Deliver native user tasks through user-task listener jobs; supply native user-task completion |
| `CUSTOM` | Supply neither built-in user-task delivery nor user-task completion; provide application beans |

`SUBSCRIPTION_REFRESHING` uses the service-task worker ID and a worker lock timeout equal to twice
the schedule interval, converted to milliseconds.

In `LISTENER` mode, configure BPMN listeners to use the listener topic or enable global listener registration.
Global user-task listeners, including static global configuration and REST registration, require
[Camunda 8.9+](https://docs.camunda.io/docs/8.9/reference/announcements-release-notes/890/890-release-notes/#global-user-task-listeners).
Registration remains opt-in and never silently falls back to polling.

This is an engine feature prerequisite, not a promise of adapter support for older SDK/server families.
The current fixtures use global registration on the default `8.10.7` server. They do not cover BPMN-defined listeners.

Polling uses ordinary variable searches and fetches explicitly truncated values by variable key.
It does not send the full-value search option introduced in server `8.8.5`.
Startup runs optional global registration, optional preload of existing tasks, and then subscription, in that order.
A registration failure prevents preload and subscription. A preload exception logs the failure and allows subscription to continue.

## Register subscriptions before delivery starts

Register subscriptions in an ordinary CDI `StartupEvent` observer. Its default priority of `2500` runs before the
adapter's startup observer at priority `3000`. If you assign a custom observer priority, use a value less than `3000`.
Do not rely on `@PostConstruct` on an otherwise unused lazy bean or register only after application startup.

This example handles external tasks with topic `send-notification` and completes them with an output variable:

```java
package org.acme;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.TaskType;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Map;

@ApplicationScoped
public class NotificationSubscriptions {
  void register(@Observes StartupEvent event,
                TaskSubscriptionApi subscriptions,
                ServiceTaskCompletionApi completion) {
    subscriptions.subscribeForTask(new SubscribeForTaskCmd(
        CommonRestrictions.builder().build(),
        TaskType.EXTERNAL,
        "send-notification",
        null,
        (task, variables) -> completion.completeTask(new CompleteTaskCmd(
            task.getTaskId(), () -> Map.of("notificationSent", true))).join(),
        (TaskInformation task) -> {})).join();
  }
}
```

The `null` requests all variables. The final callback handles task termination; this example needs no cleanup.
Register service subscriptions before startup delivery and restart to change their worker set;
the core service delivery opens workers from the subscriptions present when it starts.
Removing a service subscription does not close its already-opened worker; the worker stays open until adapter shutdown.
Do not rely on hot subscription registration or removal to manage service workers.
The extension's shutdown observer uses priority `2000` to stop its owned schedules and workers.
CDI then disposes the extension's default client. The producer's `@Disposes` method closes only its own product.
Lifecycle regression tests exercise natural Quarkus shutdown and failed-startup teardown with the real default
client and test-owned worker/delivery mocks; they also check independent application-client disposal.
Dev-mode reload relies on the same teardown mechanism, but closing old resources and using a replacement
client across an actual reload remain unverified. JVM shutdown tests do not establish dev-mode reload coverage.
The adapter never closes an application-supplied client. Its application producer must provide a disposer
or equivalent ownership-aware cleanup after adapter workers stop.

## Override defaults

Application CDI beans replace extension defaults. Use `@Produces` with `@Singleton` for a custom
`SubscriptionRepository`, `FailureRetrySupplier`, or API implementation. For example, replace the default in-memory repository:

```java
package org.acme;

import dev.bpmcrafters.processengineapi.impl.task.InMemSubscriptionRepository;
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class AdapterBeans {
  @Produces
  @Singleton
  SubscriptionRepository subscriptions() {
    return new InMemSubscriptionRepository();
  }
}
```

Select `CUSTOM` explicitly when your application owns delivery. Supply `UserTaskCompletionApi` yourself
in user-task `CUSTOM` mode if your application needs it. The extension does not silently select a fallback strategy.

For example, own a custom client and its cleanup in the application:

```kotlin
@Singleton
class ApplicationClient {
  @Produces
  @Singleton
  fun client(): CamundaClient = CamundaClient.newClientBuilder()
    .applyEnvironmentVariableOverrides(false)
    .grpcAddress(URI("http://localhost:26500"))
    .restAddress(URI("http://localhost:8080"))
    .build()

  fun close(@Disposes client: CamundaClient) = client.close()
}
```

Use `io.camunda.client.CamundaClient`, `java.net.URI`, `jakarta.inject.Singleton`, and
`jakarta.enterprise.inject.Produces` / `Disposes` imports. The producer is an ordinary application bean;
the extension's [default bean](https://quarkus.io/guides/cdi-reference#default_beans) yields to it.

## Supported target

The supported target and required CI coverage are JVM-only. Native image builds are not required by this project.
The integration application retains an optional native profile, but it is outside the required CI/support scope.
JVM verification does not establish native image correctness.

## Verify this repository

Use Java 17 and Docker for the full default JVM build:

```shell
./mvnw clean verify
```

Run focused extension verification from the repository root. Repeat with `SCHEDULED`,
`SUBSCRIPTION_REFRESHING`, `LISTENER`, and `CUSTOM`:

```shell
./mvnw -DskipExamples \
  -pl engine-adapter/c8-quarkus/deployment,engine-adapter/c8-quarkus/integration-tests \
  -am clean verify -B -ntp \
  -Dquarkus.process-engine-api.c8.user-tasks.delivery-strategy=SCHEDULED
```

The integration application exposes its configured strategy so tests exercise the mode fixed at build time.

`camunda.test.version` selects only the test server image, not the SDK. Retain matching `8.10.7`
SDK/server versions for current verification. Matching `8.8`/`8.9` integration requires future work;
do not use an older server image as a substitute for that migration.
Lower-platform verification remains pending and must also consume the default-built extension in a separate
Quarkus `3.37.3` / JDK 17 application without recompiling the libraries.

Verify all four strategies manually on the JVM. The full repository build exercises the default `SCHEDULED` strategy;
repeat the focused command above for each other strategy by changing the strategy property.

| User-task strategy | JVM coverage |
| --- | --- |
| `SCHEDULED` | Full repository build |
| `SUBSCRIPTION_REFRESHING` | Focused extension command |
| `LISTENER` | Focused extension command |
| `CUSTOM` | Focused extension command |

The `CUSTOM` entry uses application-owned user-task completion and manual delivery in the integration application;
it also runs the common API and DTO serialization tests. Service-task `CUSTOM` coverage stays in deployment/CDI tests,
while the common tests cover service-task completion.

Run core and Spring regression checks on Java 17:

```shell
./mvnw -DskipExamples \
  -pl engine-adapter/c8-core,engine-adapter/c8-spring-boot-starter \
  -am clean verify -B -ntp
```

The matrix describes manual verification, not additional CI jobs or a claim that every entry has already passed.
The extension implementation and tests use Kotlin; the integration example application remains Java, like the other examples.
