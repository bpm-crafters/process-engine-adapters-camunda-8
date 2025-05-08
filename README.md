# Process Engine API


[![incubating](https://img.shields.io/badge/lifecycle-INCUBATING-orange.svg)](https://github.com/holisticon#open-source-lifecycle)
[![Development branches](https://github.com/bpm-crafters/process-engine-adapters-camunda-8/actions/workflows/development.yml/badge.svg)](https://github.com/bpm-crafters/process-engine-adapters-coamunda-8/actions/workflows/development.yml)
[![Maven Central Version](https://img.shields.io/maven-central/v/dev.bpm-crafters.process-engine-adapters/process-engine-adapter-camunda-platform-c8-bom)](https://maven-badges.herokuapp.com/maven-central/dev.bpm-crafters.process-engine-adapters/process-engine-adapter-camunda-platform-c8-bom)
[![Camunda Platform 8](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%208-26d07c)](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%208-26d07c)



## Purpose of the library

This library provides an adapter implementation of Process Engine API for Camunda 8 process engine.

## Anatomy

The library contains of the following Maven modules:

- `process-engine-adapters-camunda-platform-c8-embedded-core`: Camunda 8 Platform Embedded Adapter implementation
- `process-engine-adapters-camunda-platform-c8-embedded-spring-boot-starter`: Camunda 8 Platform Embedded Adapter Spring Boot Starter
- `process-engine-adapter-camunda-platform-c8-bom`: Maven BOM with providing dependencies and versions

## Usage

If you want to start usage, please add the following dependency to your Maven project:

```xml
<dependency>
  <groupId>dev.bpm-crafters.process-engine-adapters</groupId>
  <artifactId>process-engine-adapters-camunda-platform-c8-embedded-spring-boot-starter</artifactId>
  <version>${process-engine-adapter-camunda-platform-c8.version}</version>
</dependency>
```

and provide other required dependencies:

- spring-boot-starter-camunda-sdk
- camunda-tasklist-client-java

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

## Compatibility

| Adapter-8 Version | Camunda 8 Version | API Version |
|-------------------|-------------------|-------------|
| 2025.05.1         | 8.7.1             | 1.1         |
| 2025.04.1         | 8.6.12            | 1.0         |




