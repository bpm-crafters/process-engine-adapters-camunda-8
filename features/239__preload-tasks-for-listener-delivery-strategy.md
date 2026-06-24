# Preload Tasks for Listener Delivery Strategy

## Context

[Feature 232](232__user-task-listener-delivery-strategy-plan.md) introduced the `LISTENER` user task delivery strategy. It consumes Camunda user task listener
jobs and keeps the local `SubscriptionRepository` aligned with task lifecycle events after the listener worker is active.

The missing case is startup catch-up. User tasks that already reached `UserTaskState.CREATED` before this application
starts do not emit a new listener job for this adapter instance. They are therefore invisible to `ListenerUserTaskDelivery`
unless another mechanism performs a native user task search.

The existing `PullUserTaskDelivery.refresh()` already performs the search needed for this catch-up:

- reads user task subscriptions from the same `SubscriptionRepository`
- searches native Camunda user tasks in `UserTaskState.CREATED`
- matches tasks against subscription task description keys and supported restrictions
- fetches forms and variables
- filters variables by the active subscription payload description
- invokes the existing subscription action callback
- activates the delivered task id in the repository

The listener strategy should reuse that behavior once during startup, without turning `LISTENER` back into a scheduled
polling strategy.

## Feature Outcome

When `user-tasks.delivery-strategy=LISTENER` is active, the adapter optionally preloads already-created native user tasks
once during startup by calling `PullUserTaskDelivery.refresh()`. After that one-shot preload, user task delivery
continues through listener jobs only.

## Implemented Strategy

A dedicated listener preload puller is coordinated from the listener startup binding.

The main `c8-user-task-delivery` bean remains `ListenerUserTaskDelivery` for `LISTENER`. A separate pull delivery bean is
created only for preload:

```kotlin
@Bean("c8-user-task-listener-preload-delivery")
@Qualifier("c8-user-task-listener-preload-delivery")
@ConditionalOnUserTaskDeliveryStrategy(strategy = LISTENER)
fun listenerUserTaskPreloadDelivery(
  subscriptionRepository: SubscriptionRepository,
  camundaClient: CamundaClient,
): PullUserTaskDelivery =
  PullUserTaskDelivery(
    subscriptionRepository = subscriptionRepository,
    camundaClient = camundaClient
  )
```

The different bean name ensures the listener strategy does not collide with the existing `c8-user-task-delivery` bean or
change the public delivery strategy type.

`UserTaskListenerDeliveryBinding` becomes responsible for the startup sequence:

1. Register the global user task listener when auto-registration is enabled.
2. If listener preloading is enabled, call `PullUserTaskDelivery.refresh()` once.
3. Open the listener worker with `ListenerUserTaskDelivery.subscribe()`.

This order avoids a startup race where a visible task could be delivered by both the listener worker and the preload
search at the same time. Tasks created after global listener registration but before the worker opens are backed by
listener jobs and wait briefly until the worker starts.

## Configuration

The listener property is:

```kotlin
data class UserTaskListener(
  // existing properties...
  val preloadExistingTasks: Boolean = true,
)
```

Spring Boot property:

```yaml
dev:
  bpm-crafters:
    process-api:
      adapter:
        c8:
          user-tasks:
            delivery-strategy: LISTENER
            listener:
              preload-existing-tasks: true
```

Defaulting to `true` makes `LISTENER` deliver the expected startup state without additional user configuration. Setting
it to `false` keeps the current pure-listener behavior for deployments that do not want a startup user task search.

## Delivery Semantics

The preload uses the exact `PullUserTaskDelivery.refresh()` semantics:

- matching `CREATED` user tasks are delivered with `TaskInformation.CREATE` when they are not already active locally
- already active delivered task ids can be delivered with `TaskInformation.UPDATE`
- delivered task ids no longer returned by the search can produce `TaskInformation.DELETE`
- action callback failures are handled by `PullUserTaskDelivery` and do not activate the failed task

With the default in-memory repository, startup preload normally delivers only `CREATE` for matching tasks because there
are no remembered delivered task ids. A custom persistent `SubscriptionRepository` may see `UPDATE` or `DELETE` during
startup reconciliation, which is consistent with pull delivery behavior.

The preload does not replace listener lifecycle events:

- task assignment, update, completion, and cancellation after worker startup remain listener-driven
- the listener worker is still the only continuous delivery mechanism under `LISTENER`
- no scheduled `PullUserTaskDelivery` binding is created for `LISTENER`

## Startup Ordering and Failure Policy

The binding does not let a preload failure prevent future listener delivery. A request-level failure during `refresh()`
is logged with a stable diagnostic message and the listener worker is still opened.

Recommended structure:

```kotlin
globalUserTaskListenerRegistrationHelper.registerIfEnabled()

if (c8AdapterProperties.userTasks.listener.preloadExistingTasks) {
  try {
    listenerPreloadUserTaskDelivery.refresh()
  } catch (e: Exception) {
    logger.error(e) { "PROCESS-ENGINE-C8-115: Failed to preload existing user tasks for listener delivery." }
  }
}

listenerUserTaskDelivery.subscribe()
```

Global listener registration keeps its existing behavior. If registration fails, the binding should not silently continue,
because the configured listener infrastructure may be absent.

## Subscription Timing

Preload only sees subscriptions already present in `SubscriptionRepository` when the `ApplicationStartedEvent` listener
runs. This matches the current listener startup model, where `ListenerUserTaskDelivery.subscribe()` computes the worker
variable fetch projection from the subscriptions available at startup.

Applications that create user task subscriptions after startup will still receive future listener events, but existing
tasks matching those late subscriptions are not discovered by this one-shot preload.

## Multi-Instance Behavior

Every application instance that starts with `LISTENER` and `preload-existing-tasks=true` runs the one-shot search against
its own local subscriptions. This is different from listener jobs, where Camunda activates a listener job for only one
worker instance.

This is acceptable for cache warm-up or local subscription state, but deployments with side-effecting subscription actions
must account for possible duplicate preload delivery across application instances. Shared subscription state or
application-level idempotency remains necessary when only one delivery side effect is allowed.

## Implementation

- `C8AdapterProperties.UserTaskListener` exposes `preloadExistingTasks: Boolean = true`.
- `C8CamundaClientAutoConfiguration` creates `c8-user-task-listener-preload-delivery` as a `PullUserTaskDelivery` bean
  when `LISTENER` is active.
- `UserTaskListenerDeliveryBinding` receives the preload puller and `C8AdapterProperties`.
- `scheduleUserTaskListenerSubscription` runs registration, optional one-shot preload, and listener subscription in that
  order.
- `C8SchedulingAutoConfiguration` remains unchanged; no `c8-user-task-delivery-scheduler` bean is created for
  `LISTENER`.

## Test Strategy

Spring Boot auto-configuration coverage in `C8UserTaskDeliveryStrategyAutoConfigurationTest`:

- `LISTENER` still exposes `c8-user-task-delivery` as `ListenerUserTaskDelivery`
- `LISTENER` exposes `c8-user-task-listener-preload-delivery` as `PullUserTaskDelivery`
- `LISTENER` still exposes `c8-user-task-delivery-subscription` as `UserTaskListenerDeliveryBinding`
- `SCHEDULED` and `SUBSCRIPTION_REFRESHING` bean graphs remain unchanged

Focused binding unit coverage in `UserTaskListenerDeliveryBindingTest`:

- registration, preload, and listener subscription happen in this order when preload is enabled
- preload is skipped when `preload-existing-tasks=false`
- listener subscription still happens when preload throws
- global listener registration failure still prevents startup sequencing from continuing


## Non-Goals

- Do not add periodic polling to the `LISTENER` strategy.
- Do not change listener event semantics from feature 232.
- Do not change `PullUserTaskDelivery` matching behavior as part of this feature.
- Do not solve late-subscription catch-up after application startup.
- Do not solve cross-instance exactly-once delivery.
