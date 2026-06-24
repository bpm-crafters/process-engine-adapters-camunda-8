# AGENTS.md

## 1. Overview

This repository provides Camunda 8 implementations of the BPM Crafters Process Engine API. It is a Maven multi-module project with Kotlin adapter libraries, Spring Boot autoconfiguration, a BOM, reusable test support, and Java example applications.

## 2. Folder Structure

- `pom.xml`: root Maven build, dependency management, Kotlin/JVM 17 compiler setup, JaCoCo, and the default module list.
- `engine-adapter`: production adapter libraries and reusable test support.
  - `c8-core`: Kotlin implementation of Process Engine API ports over the Camunda Java client.
    - `correlation`, `deploy`, `decision`, `process`: API implementations for messages, signals, deployments, decisions, and process starts.
    - `task`: user task and service task subscription, delivery, completion, modification, and variable serialization logic.
    - `testing`: Camunda 8 process test stages/helpers used by integration-style tests and examples.
    - `src/test/kotlin`: focused unit tests for mappings, matching, completion, modification, and decision behavior.
  - `c8-spring-boot-starter`: Spring Boot autoconfiguration for wiring the core adapter into applications.
    - `springboot`: properties, conditions, Camunda client configuration, and primary adapter beans.
    - `springboot/subscription`: lifecycle bindings for subscription-based task delivery.
    - `springboot/schedule`: scheduled and refreshing user-task delivery bindings.
    - `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: exported autoconfiguration entry point.
    - `src/test/kotlin`: Camunda process test integration tests for starter wiring and API behavior.
  - `adapter-testing`: shared JGiven, Spring, Camunda process test, Awaitility, AssertJ, and Testcontainers fixtures for adapter tests.
- `bom`: Maven BOM that pins adapter and Camunda dependencies for consumers.
- `examples`: Java example applications and shared fixtures.
  - `java-common-fixture`: ports, adapters, task handlers, workflow constants, and shared Spring configuration.
  - `java-c8` and `java-c8-sb3`: runnable example applications with BPMN, application profiles, Docker Compose files, HTTP demo requests, and JGiven scenario tests.
- `docs`: project documentation such as SaaS quickstart guidance.
- `features`: project feature implementation plans, named with an issue number follwed by caption.
- `.github/workflows`: CI and release automation; development builds run with Java 17 and `./mvnw clean verify -U -B -ntp -T4`.
- `.mvn/wrapper`, `mvnw`, `mvnw.cmd`: checked-in Maven wrapper; prefer it over a globally installed Maven.
- `target`: generated build output. Do not edit or review it as source.

## 3. Core Behaviors & Patterns

- Adapter classes implement Process Engine API interfaces from `dev.bpmcrafters.processengineapi` and translate command objects into Camunda client command builders.
- Public adapter methods usually return `CompletableFuture`; implementations either wrap blocking Camunda `.send().get()` calls in `CompletableFuture.supplyAsync` or return `CompletableFuture.completedFuture` after synchronous completion.
- Camunda restrictions are represented through `CommonRestrictions` maps. Add restriction support explicitly in mapper/helper functions and fail closed for unsupported command variants.
- Task delivery is subscription-centered. `SubscriptionRepository` tracks subscriptions and delivered task IDs; delivery implementations call task actions with `TaskInformation` and reason markers such as `CREATE`, `COMPLETE`, and `DELETE`.
- Spring Boot wiring is conditional and strategy-driven. Add beans through autoconfiguration classes, guard them with the existing condition annotations, and register new autoconfiguration through `AutoConfiguration.imports`.
- Logging uses a private top-level `KotlinLogging.logger {}` and stable diagnostic message IDs like `PROCESS-ENGINE-C8-004`; preserve that style when adding new operational logs.
- Tests use JUnit 5 with AssertJ and Mockito/MockK for unit coverage. Integration-style process tests use Camunda Process Test and JGiven stages with readable backtick method names in Kotlin or snake_case scenario methods in Java.

## 4. Conventions

- Kotlin source uses two-space indentation, constructor injection, expression-bodied simple functions, and local extension functions for Camunda mapping logic.
- Java example code uses Spring stereotypes/configuration, Lombok where already present, and ports/adapters naming under `application.port.*` and `adapter.*`.
- Keep module boundaries intact: Camunda client translation belongs in `c8-core`; Spring bean wiring and properties belong in `c8-spring-boot-starter`; shared scenario/test helpers belong in `adapter-testing`; runnable demonstrations belong in `examples`.
- Name implementation classes after the API or behavior they adapt, usually with `Impl`, `Delivery`, `Binding`, `AutoConfiguration`, `Condition`, or `Properties` suffixes.
- Keep comments sparse and purposeful. Existing comments document configuration properties, lifecycle caveats, and known TODO/FIXME items; do not add narration for obvious code.
- Use root-managed Maven versions. Add dependency versions to the root build or BOM when they affect published consumers; avoid hard-coded module-local versions unless the existing module already owns that concern.
- Prefer the Maven wrapper for verification. Useful scopes include `./mvnw clean verify`, `./mvnw -pl engine-adapter/c8-core test`, and `./mvnw -pl engine-adapter/c8-spring-boot-starter test`.

## 5. Working Agreements

- Respond in English by default; keep technical terms and code blocks unchanged.
- Before editing, search related usages, tests, and module wiring for the same API, bean, restriction, or task flow.
- Keep changes focused and colocated with the existing module responsibility.
- Do not add tests, lint, format tasks, dependencies, or abstractions unless they are needed for the requested change.
- Prefer explicit failures over speculative fallback behavior; this codebase does not require backwards compatibility unless the user asks for it.
- Do not edit generated `target` output or unrelated files.
- If a change affects public adapter behavior, Spring autoconfiguration, or examples, verify the relevant Maven module or explain why verification was not run.
