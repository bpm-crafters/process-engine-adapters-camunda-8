# Quarkus Adapter Module

> No GitHub issue existed at the time of writing; rename this file to `<issue-number>__quarkus-adapter-module.md` once
> an issue is created.

## Context

The adapter is split into the framework-free `c8-core` (all classes take `io.camunda.client.CamundaClient` and
`SubscriptionRepository` via constructors, zero Spring imports) and the `c8-spring-boot-starter` holding all framework
wiring. Quarkus applications could not use the adapter without hand-building that wiring: config binding, bean
creation, strategy-conditional deliveries, startup subscription, and the fixed-rate refresh scheduler.

A worker abstraction analogous to `bpm-crafters/process-engine-worker` was considered and deliberately excluded: that
library has no framework-agnostic core (its annotations use Spring's `@AliasFor`, discovery runs through a
`BeanPostProcessor`), and a Quarkus `@ProcessEngineWorker` equivalent requires a full Quarkus extension with Jandex
discovery — a follow-up in the process-engine-worker repository, not here.

## Feature Outcome

A new module `engine-adapter/c8-quarkus` (`process-engine-adapter-camunda-platform-c8-quarkus`) makes the adapter
usable from Quarkus applications by adding the adapter dependency plus `io.quarkiverse.camunda:quarkus-camunda`
(which provides the `CamundaClient` CDI bean and Camunda dev services — the analog of `camunda-spring-boot-starter`
for the Spring starter). Same property tree, same delivery strategies, same log message IDs. A runnable example lives
in `examples/java-c8-quarkus`.

## Implemented Strategy

The module is a plain CDI library (jandex-indexed via `io.smallrye:jandex-maven-plugin`), not a runtime/deployment
Quarkus extension: it only produces beans from framework-free core classes and needs no annotation discovery, build
steps, or recorders. The Quarkus dependencies (`quarkus-arc`, `quarkus-core`, `smallrye-config`) are `provided`; the
consuming application's `quarkus-bom` decides the versions. The one exception is `quarkus-hibernate-validator`, which
is a compile dependency because SmallRye silently skips every constraint when no `ConfigValidator` is on the
classpath.

The Spring starter's classes map to Quarkus counterparts in `dev.bpmcrafters.processengineapi.adapter.c8.quarkus`:

| Spring Boot starter | c8-quarkus | Mechanism |
|---|---|---|
| `C8AdapterProperties` (`@ConfigurationProperties`) | `C8AdapterProperties` | `@ConfigMapping` interface, same prefix `dev.bpm-crafters.process-api.adapter.c8`, `@Unremovable`; Bean Validation constraints on the values plus a `@ValidC8AdapterConfiguration` class level constraint for the properties that are only required once `enabled` is `true` (they stay `Optional` because the mapping is bound even for a disabled adapter). The `required*()` unwrapping accessors are interface default methods, which SmallRye does not treat as properties |
| `C8AdapterEnabledCondition` | `requireEnabled()` in producers | beans exist but fail fast on first use when disabled (`enabled` defaults to `false`) |
| `C8AdapterAutoConfiguration` | `C8AdapterProducers` | producer methods with interface return types (ArC client proxies work on the interface, so final Kotlin impl classes are fine) |
| `C8CamundaClientAutoConfiguration` + `@ConditionalOn*Strategy` (delivery beans) | `C8TaskDeliveryProducers` | one producer per strategy, guarded by `@LookupIfProperty` with `StringValueMatch.REGEX`. `CUSTOM` and an unset key match nothing, so no bean exists — the CDI equivalent of Spring's bean absence. The conditions are regular expressions because `@LookupIfProperty` compares the raw property string while the config mapping converts the enum, and an equality match would miss `subscription-refreshing`. `C8AdapterLifecycle` cross-checks the two on startup (log id `129`) |
| `C8SubscriptionAutoConfiguration` bindings | `C8AdapterBindings` | an internal `@Singleton` that only decides what to call on the deliveries it is given; the deliveries arrive as `Instance` handles, which is also where the nullability of `subscribingUserTaskDelivery` now comes from |
| `C8CamundaClientAutoConfiguration` (completion and modification apis) | `C8CamundaClientProducers` | still a runtime switch behind a single `@DefaultBean` producer. These types are injected directly by applications, and two producers of one type would be an ambiguous dependency at build time whatever the lookup condition says |
| `C8SubscriptionAutoConfiguration` startup (`@EventListener @Async` on `ApplicationStartedEvent`) | `C8AdapterLifecycle.onStart` | `@Observes StartupEvent` at `@Priority(APPLICATION + 900)`, so applications register task subscriptions in own startup observers (default priority) before the deliveries subscribe; subscription runs async on the adapter scheduler |
| `C8SchedulingAutoConfiguration` (`ThreadPoolTaskScheduler`, `SchedulingConfigurer`) | `C8AdapterLifecycle` | plain `ScheduledExecutorService` (2 threads, `C8REMOTE-SCHEDULER-*`), fixed-rate `refresh()` for `SCHEDULED`/`SUBSCRIPTION_REFRESHING`; no quarkus-scheduler dependency forced on consumers |

Overridable beans (`SubscriptionRepository`, `EvaluateDecisionApi`, `FailureRetrySupplier`, completion and
modification apis) are `@io.quarkus.arc.DefaultBean` — the CDI analog of `@ConditionalOnMissingBean`.

New log ids: `120` (disabled, lifecycle skipped), `121` (startup subscription failed), `122` (refresh failed),
`123` (lifecycle stopped), `124/125` (refreshing user tasks tick), `126/127` (delivering user tasks tick),
`128` (user task subscription failed), `129` (configured strategy produced no delivery bean),
`204` (Quarkus wiring config report). Ids `100–115` are reused verbatim from the Spring bindings.

Versions are pinned in the root pom: `quarkus.version=3.38.1` (the version quarkus-camunda 2.1.1 is built against)
and `quarkus-camunda.version=2.1.1` (which resolves `io.camunda:camunda-client-java` from the same
`camunda.version` the rest of the project uses). The BOM manages the new artifact and `quarkus-camunda`.

Known limitations, matching Spring behavior where noted:

- `SUBSCRIPTION` service task workers are opened once on startup; later subscriptions are not picked up (same as
  Spring, where handlers register during bean initialization).
- quarkus-camunda 2.1.1 supports OAuth/SaaS but no basic auth; `quarkus.camunda.active=false` swaps in a no-op client.
- Native image is expected to work but is not verified; a full Quarkus extension (build-time bean pruning, Dev UI,
  config doc generation) remains a possible follow-up without breaking the api.

## Verification

- `engine-adapter/c8-quarkus`: unit tests for properties validation, strategy switching in the bindings holder, and
  lifecycle scheduling; `C8QuarkusAdapterITest` (`@QuarkusTest`, Camunda dev services container) asserts all api beans
  are injectable — including the `@ConfigMapping` with the hyphenated prefix — and runs a deploy → start →
  service task → scheduled user task delivery → completion → process-completed round trip.
- `examples/java-c8-quarkus`: `SimpleProcessQuarkusTest` runs the demo flow (deploy, start, complete user task,
  correlate message, assert instance completed) over REST against the dev services broker; the module's
  `simple-process-demo.http` (java-c8 copy plus an explicit deploy step and a media-type-tolerant content-type
  assertion) drives `quarkus:dev`.
