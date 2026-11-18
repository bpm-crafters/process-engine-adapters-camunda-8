# Quarkus adapter

Use this extension to inject the Process Engine API into a Quarkus application.

## Install

Import the Quarkus platform BOM and adapter BOM in your application's `pom.xml`

```xml
<properties>
  <quarkus.platform.version>3.40.1.1</quarkus.platform.version>
  <process-engine-adapter-camunda-platform-c8.version>${camunda.sdk.version}</process-engine-adapter-camunda-platform-c8.version>
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

Without OAuth credentials the default client uses no authentication. Partial or blank OAuth credentials fail explicitly.

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
