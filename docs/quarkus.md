# Quarkus adapter

Use this extension to inject the Process Engine API into a Quarkus application and run it on the JVM.

## Versions and prerequisites

| Component | Version / requirement |
| --- | --- |
| Adapter | `2026.09.2-SNAPSHOT` (**unreleased**, not a Maven Central release) |
| Quarkus platform | `3.40.1.1` |
| Quarkiverse Camunda | `2.1.3`, brought in transitively |
| Camunda | `8.10.7` |
| Application and full repository build JDK | Java 17 |
| Core and Spring artifacts | Continue to target Java 17 |
| Integration tests | Docker |

Build/install the development artifacts locally or use your configured snapshot repository before consuming this unreleased version.
The Quarkus dependency management stays inside `engine-adapter/c8-quarkus`; the core does not depend on Quarkus or Spring.

[Quarkus `3.40.1.1`](https://quarkus.io/blog/quarkus-3-40-1-1-released/) is the latest stable release compatible with Java 17 as of 2026-11-05.
Quarkiverse Camunda `2.1.3` targets Java 17; its `3.0.x` releases require Java 21.
Repository dependency management overrides `2.1.3`'s upstream Camunda `8.9.21` default with `8.10.7`.

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

quarkus.camunda.client.broker.gateway-address=http://localhost:26500
quarkus.camunda.client.broker.rest-address=http://localhost:8080
quarkus.camunda.devservices.enabled=false
# Disable automatic resource deployment when using DeploymentApi yourself.
quarkus.camunda.resources.enabled=false
```

The adapter owns `quarkus.process-engine-api.c8.*`. Quarkiverse owns the shared Camunda client and
`quarkus.camunda.*` connection, authentication, and client settings. Follow the
[Quarkiverse Camunda guide](https://docs.quarkiverse.io/quarkus-camunda/dev/index.html)
for SaaS or self-managed authentication; select settings appropriate to version `2.1.3`.
Do not copy credentials into source control.

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
The extension closes its owned schedules and workers at shutdown and dev-mode reload; its shutdown observer uses priority `2000`.
Quarkiverse retains ownership of the shared Camunda client.

### Known upstream limitations

Quarkiverse Camunda `2.1.3` retains the same active-client, authentication, configuration, serialization,
and native registrations as `3.0.2`. Quarkiverse owns the shared `CamundaClient`; the adapter does not reimplement it.

Pinned Quarkiverse Camunda `2.1.3` uses the annotation types `io.quarkus.runtime.Startup` and
`io.quarkus.runtime.Shutdown` as client lifecycle observer parameters, rather than `StartupEvent` and `ShutdownEvent`
([pinned source](https://github.com/quarkiverse/quarkus-camunda/blob/2.1.3/runtime/src/main/java/io/quarkiverse/camunda/runtime/LifecycleManager.java)).
Do not assume automatic shared-client shutdown or dev-reload cleanup from the adapter's worker cleanup.
The adapter closes only its owned workers and scheduler; it does not work around the upstream client lifecycle.
This is a limitation of the pinned dependency, not a new adapter regression. Client leakage has not been measured empirically.

`quarkus.camunda.active=false` selects Quarkiverse's `NoOpClient`; the default `true` uses the real client.
This setting differs from disabling the adapter (`quarkus.process-engine-api.c8.enabled=false`) or Dev Services.
The `2.1.3` no-op client lacks newer Camunda `8.10` abstract methods/builders; calling them can throw
`AbstractMethodError`. Current adapter paths do not call those missing methods, and deployment/CDI tests use
the no-op client. The adapter supplies no fallback client or no-op compatibility fix.

The older `2.1.3` Dev Services implementation has not been verified with Camunda `8.10.7`.
This repository uses an explicitly managed server with `quarkus.camunda.devservices.enabled=false`,
as shown above; do not infer universal Dev Services compatibility.

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
