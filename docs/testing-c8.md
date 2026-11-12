---
title: Camunda 8 testing
---

# Camunda 8 testing

Use `process-engine-adapter-camunda-platform-c8-process-testing` with qualifier `c8`. Each test scenario starts Camunda Process Test's default Testcontainers runtime with a Camunda 8 engine and uses it as the process-test engine. The runtime is closed again after the scenario. A working Docker or compatible container runtime is therefore required to execute these tests.

## Maven dependencies

Add the common BDD testing API and the Camunda 8 testing provider to the test class path:

```xml
<dependencies>
  <dependency>
    <groupId>dev.bpm-crafters.process-engine-api-testing</groupId>
    <artifactId>process-engine-api-testing-jgiven</artifactId>
    <version>${process-engine-api-testing.version}</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>dev.bpm-crafters.process-engine-adapters</groupId>
    <artifactId>process-engine-adapter-camunda-platform-c8-process-testing</artifactId>
    <version>${process-engine-adapter-c8.version}</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

## First scenario

Select the `c8` initializer and write scenarios against the common testing API. The same scenario shape can be used with another supported engine provider.

=== "Kotlin"

    ```kotlin
    @UseProcessTestInitializer(C8ProcessTestInitializer.QUALIFIER)
    class OrderProcessTest : ProcessScenarioTest<OrderActions, OrderAssertions>() {
      @Test fun `order waits for approval`() {
        given {
          processIsDeployed("bpmn/order-approval.bpmn")
          processIsStartedByDefinition("order-approval")
        }
        then {
          processWaitsInUserTask("approve-order")
        }
      }
    }

    open class OrderActions : ActionStage<OrderActions>()
    open class OrderAssertions : AssertStage<OrderAssertions>()
    ```

=== "Java"

    ```java
    @UseProcessTestInitializer(C8ProcessTestInitializer.QUALIFIER)
    class OrderProcessTest extends ProcessScenarioTest<OrderActions, OrderAssertions> {
      @Test
      void orderWaitsForApproval() {
        given()
          .processIsDeployed("bpmn/order-approval.bpmn")
          .processIsStartedByDefinition("order-approval");
        then()
          .processWaitsInUserTask("approve-order");
      }
    }

    class OrderActions extends ActionStage<OrderActions> {}
    class OrderAssertions extends AssertStage<OrderAssertions> {}
    ```

## Configuration

The provider supports the common process-testing operations and time travel. Deployment visibility
is eventually consistent, so it waits before starting a newly deployed process.

| Setting                                               | Default | Where to set it          | Purpose                                          |
| ----------------------------------------------------- | ------- | ------------------------ | ------------------------------------------------ |
| `c8.deployment-propagation-delay`                     | `PT1S`  | Annotation configuration | Wait after deployment before starting a process. |
| `bpmcrafters.c8.testing.deployment-propagation-delay` | Not set | JVM system property      | Default delay for C8 tests.                      |

Values are ISO-8601 `Duration` values. For example, set `c8.deployment-propagation-delay=PT2S`
through `@UseProcessTestInitializer(configuration = ["c8.deployment-propagation-delay=PT2S"])`.
Annotation configuration overrides the system property.
