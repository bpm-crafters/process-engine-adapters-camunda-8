# Process Engine Adapter Camunda 8


[![incubating](https://img.shields.io/badge/lifecycle-INCUBATING-orange.svg)](https://github.com/holisticon#open-source-lifecycle)
[![Development branches](https://github.com/bpm-crafters/process-engine-adapters-camunda-8/actions/workflows/development.yml/badge.svg)](https://github.com/bpm-crafters/process-engine-adapters-coamunda-8/actions/workflows/development.yml)
[![Maven Central Version](https://img.shields.io/maven-central/v/dev.bpm-crafters.process-engine-adapters/process-engine-adapter-camunda-platform-c8-bom)](https://maven-badges.herokuapp.com/maven-central/dev.bpm-crafters.process-engine-adapters/process-engine-adapter-camunda-platform-c8-bom)
[![Camunda Platform 8](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%208-26d07c)](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%208-26d07c)


## Purpose of the library

This library provides an adapter implementation of Process Engine API for Camunda 8 process engine.

## Compatibility

| Adapter-8 Version                                                                                             | Camunda 8 Version | API Version |
|---------------------------------------------------------------------------------------------------------------|-------------------|-------------|
| [2026.06.2](https://github.com/bpm-crafters/process-engine-adapters-camunda-8/releases/tag/2026.06.2)         | 8.9.9             | 1.7         |
| [2026.06.1](https://github.com/bpm-crafters/process-engine-adapters-camunda-8/releases/tag/2026.06.1)         | 8.9.6             | 1.6         |
| [2026.04.1](https://github.com/bpm-crafters/process-engine-adapters-camunda-8/releases/tag/2026.04.1)         | 8.8.21            | 1.5         |
| [2026.02.2](https://github.com/bpm-crafters/process-engine-adapters-camunda-8/releases/tag/2026.02.2)         | 8.8.14            | 1.5         |
| 2026.01.1                                                                                                     | 8.8.0             | 1.5         |
| 2025.11.1                                                                                                     | 8.8.0             | 1.4         |
| 2025.05.2                                                                                                     | 8.7.3             | 1.2         |
| 2025.05.1                                                                                                     | 8.7.2             | 1.1         |
| 2025.04.1                                                                                                     | 8.6.12            | 1.0         |

Building this project requires **JDK 17 to 25** (enforced with a clear error). JDK 26+ is not supported yet, because
JGiven/Byte Buddy stage creation breaks with Unsafe disabled by default
([TNG/JGiven#2224](https://github.com/TNG/JGiven/issues/2224), fix merged but unreleased) and Spring Boot 3.5 /
Quarkus 3.x support at most Java 25.

## 📚 Documentation

The documentation can be found [here](https://bpm-crafters.github.io/process-engine-api-docs/stable/) or in its
respective [repository](https://github.com/bpm-crafters/process-engine-api-docs).

## Usage

### Spring Boot

If you want to start usage, please add the adapter starter to your Maven project:

```xml
<dependency>
  <groupId>dev.bpm-crafters.process-engine-adapters</groupId>
  <artifactId>process-engine-adapter-camunda-platform-c8-spring-boot-starter</artifactId>
  <version>${process-engine-adapter-camunda-platform-c8.version}</version>
</dependency>
```

The adapter starter is meant to be used together with the Camunda Spring Boot starter:

```xml
<dependency>
  <groupId>io.camunda</groupId>
  <artifactId>camunda-spring-boot-starter</artifactId>
</dependency>
```

For Spring Boot 3 projects, use the Spring Boot 3 Camunda starter instead:

```xml
<dependency>
  <groupId>io.camunda</groupId>
  <artifactId>camunda-spring-boot-3-starter</artifactId>
</dependency>
```

For Spring Boot 3, you currently also need to add Apache HttpComponents explicitly in the versions used by the
working `examples/java-c8-sb3` sample:

```xml
<dependency>
  <groupId>org.apache.httpcomponents.client5</groupId>
  <artifactId>httpclient5</artifactId>
  <version>5.6.1</version>
</dependency>
<dependency>
  <groupId>org.apache.httpcomponents.core5</groupId>
  <artifactId>httpcore5</artifactId>
  <version>5.4.2</version>
</dependency>
<dependency>
  <groupId>org.apache.httpcomponents.core5</groupId>
  <artifactId>httpcore5-h2</artifactId>
  <version>5.4.2</version>
</dependency>
```

### Quarkus

For Quarkus projects, add the Quarkus adapter library together with the
[Quarkiverse Camunda extension](https://github.com/quarkiverse/quarkus-camunda), which provides the `CamundaClient`
CDI bean and Camunda dev services:

```xml
<dependency>
  <groupId>dev.bpm-crafters.process-engine-adapters</groupId>
  <artifactId>process-engine-adapter-camunda-platform-c8-quarkus</artifactId>
  <version>${process-engine-adapter-camunda-platform-c8.version}</version>
</dependency>
<dependency>
  <groupId>io.quarkiverse.camunda</groupId>
  <artifactId>quarkus-camunda</artifactId>
</dependency>
```

See the [Quarkus quickstart](docs/quickstart-c8-quarkus.md) for configuration and lifecycle details, and
`examples/java-c8-quarkus` for a runnable example.

## Anatomy

The library contains of the following Maven modules:

- `process-engine-adapter-camunda-platform-c8-core`: Camunda 8 Platform Adapter implementation
- `process-engine-adapter-camunda-platform-c8-spring-boot-starter`: Camunda 8 Platform Adapter Spring Boot Starter
- `process-engine-adapter-camunda-platform-c8-quarkus`: Camunda 8 Platform Adapter library for Quarkus (CDI)
- `process-engine-adapter-camunda-platform-c8-testing`: shared test support for adapter and example tests
- `process-engine-adapter-camunda-platform-c8-bom`: Maven BOM with providing dependencies and versions

and provide other required dependencies, such as `camunda-spring-boot-starter`,
`camunda-spring-boot-3-starter` or `quarkus-camunda`.

If you want to rely on versions we used during creation of this library, you may want to import the BOM:

```xml
<dependency>
  <groupId>dev.bpm-crafters.process-engine-adapters</groupId>
  <artifactId>process-engine-adapter-camunda-platform-c8-bom</artifactId>
  <version>${process-engine-adapter-camunda-platform-c8.version}</version>
  <scope>import</scope>
  <type>pom</type>
</dependency>
```
