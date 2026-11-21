# Adopt Process Application Lifecycle

## Context

The Camunda 8 Spring Boot starter previously relied on independent Spring startup mechanisms:

- subscription delivery bindings listened to `ApplicationStartedEvent`
- scheduled user task delivery used `SchedulingConfigurer`
- asynchronous delivery depended on Spring's `@Async` support
- service task job workers were opened without being retained or closed during shutdown

Those mechanisms did not provide one ordered lifecycle shared by an application, its task subscriptions, and the
adapter's delivery infrastructure. In particular, user application components could register subscriptions after a
delivery worker had already started.

## Feature Outcome

The adapter now integrates with `process-engine-api-lifecycle-spring-boot` and follows the Process Engine API lifecycle.

- Application task subscriptions are registered at `SUBSCRIPTION_REGISTRATION`.
- C8 task delivery starts only when `RUNNING` is reached.
- Scheduled delivery is explicitly cancelled at `STOPPING`.
- Subscription workers and listener workers are closed or unsubscribed at `STOPPING`.
- The starter no longer enables global Spring scheduling or asynchronous listeners merely to run adapter delivery.

The lifecycle bridge is temporarily consumed as its own library while Process Engine API 1.8 is not yet released. Once
the lifecycle is published in the Process Engine API modules, the dedicated bridge dependency can be replaced by those
coordinates.

## Adapter Ordering

`C8AdapterAutoConfiguration` is ordered before `ProcessApplicationLifecycleAutoConfiguration`. This ensures that the
`TaskSubscriptionApi` exists when the lifecycle bridge constructs its subscription registration context.

The bridge drives named phases from Spring Boot and publishes synchronous Spring events for them. The C8 adapter uses
these events:

| Lifecycle event | Adapter action |
| --- | --- |
| `ProcessApplicationRunningEvent` | Start subscription-based service task delivery, subscription-refreshing user task delivery, listener user task delivery, and scheduled user task delivery. |
| `ProcessApplicationStoppingEvent` | Close service task workers, unsubscribe user task workers, close listener workers, and cancel scheduled delivery futures. |

`RUNNING` happens only after the synchronous `TaskSubscriptionRegistrationEvent` window has completed. Delivery workers
therefore observe the complete startup subscription set before their initial worker configuration or delivery run.

## Scheduled Delivery

`ScheduledUserTaskDeliveryBinding` and `RefreshingUserTaskDeliveryBinding` own their `ScheduledFuture` directly.
On `RUNNING`, each schedules its first execution after the configured interval and continues at that fixed rate. On
`STOPPING`, it cancels the future with `mayInterruptIfRunning=false` and clears its reference.

The binding avoids duplicate schedules if it receives `RUNNING` more than once. It does not use `SchedulingConfigurer`,
`@EnableScheduling`, or `@Async`.

## Service Task Worker Shutdown

`SubscribingServiceTaskDelivery` now retains every opened Camunda `JobWorker` and implements `AutoCloseable`. The
subscription binding invokes `close()` at `STOPPING`, which closes all workers and clears the local worker collection.

The existing subscription-refreshing and listener user task delivery implementations already own unsubscribe or close
operations. Their lifecycle bindings now invoke those operations at `STOPPING` as well.

## Example Applications

The shared Java example fixture no longer registers task handlers through Spring bean `initMethod` callbacks. Its service
task handlers listen for `TaskSubscriptionRegistrationEvent`, and unregister on `ProcessApplicationStoppingEvent`.
`UserTaskSupportLifecycleBinding` similarly subscribes `UserTaskSupport` during the registration event and unsubscribes
it during stopping.

This makes the examples demonstrate the intended application integration: declare subscriptions during the lifecycle's
registration window, then let the adapter begin delivery at `RUNNING`.

## Testing

`C8ProcessApplicationLifecycleIntegrationTest` starts the auto-configurations with scheduled user task delivery,
manually drives the lifecycle to `RUNNING`, and verifies that scheduling begins only then. It subsequently drives to
`STOPPING` and verifies that the scheduled future is cancelled.

Focused binding tests verify that each subscription delivery strategy starts at `RUNNING` and releases its worker
resources at `STOPPING`. The core service task delivery test verifies that opened Camunda workers are closed.

The C8 test suite now consistently uses Mockito-Kotlin; MockK dependencies and usages were removed.
