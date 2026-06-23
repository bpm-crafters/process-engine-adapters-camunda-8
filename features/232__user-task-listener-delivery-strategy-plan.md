# User Task Listener Delivery Strategy

## Context

The original native Camunda user task delivery strategy is pull-based. `PullUserTaskDelivery` searches for user tasks in
`UserTaskState.CREATED`, delivers matching tasks to the local `SubscriptionRepository`, and marks tasks that were already
delivered as updates. This works for visible `CREATED` tasks but cannot observe removals. If a user task is completed
through Tasklist, completed by another client, canceled by process termination, or interrupted by BPMN flow, it disappears
from the next search result and the local delivered-task state can become stale.

Camunda user task listeners expose user task lifecycle changes as blocking job-worker jobs. The adapter now provides an
opt-in listener-based user task delivery strategy that consumes those jobs and keeps Process Engine API subscription state
aligned with listener events instead of relying on periodic `CREATED` polling.

The default listener job type consumed by this adapter is:

```text
process-engine-user-tasks
```

The value is configurable through Spring Boot properties and is used as both the listener `type` in Camunda and the worker
job type in the adapter.

## Feature Outcome

- `LISTENER` is available as a built-in `C8AdapterProperties.UserTaskDeliveryStrategy`.
- `ListenerUserTaskDelivery` opens a Camunda job worker for user task listener jobs and implements
  `SubscribingUserTaskDelivery`.
- Native user task completion remains wired through `C8CamundaClientUserTaskCompletionApiImpl` when `LISTENER` is active.
- Existing `SCHEDULED` and `SUBSCRIPTION_REFRESHING` strategies remain available and keep their previous bean graphs.
- Optional global user task listener auto-registration is available and disabled by default.
- Listener delivery preserves existing Process Engine API task subscription semantics: task type, task description key,
  restrictions, payload description filtering, action callback, termination callback, and active delivered-task tracking.

## Version Guard

- The root build currently uses `camunda.version` `8.9.9`, where the Java client exposes global task listener APIs such as
  `newCreateGlobalTaskListenerRequest` and `newGlobalTaskListenerGetRequest`.
- Camunda 8.8 documents BPMN-level user task listeners, but the local 8.8.x Java client sources did not expose the global
  listener APIs used by auto-registration.
- For Camunda 8.8 compatibility, define BPMN-level listeners on each native user task instead of enabling global listener
  auto-registration.

## Core Implementation

The listener implementation is intentionally folded into a small set of files:

- `ListenerUserTaskDelivery.kt`
  - `ListenerUserTaskDelivery`: subscribes, unsubscribes, consumes activated listener jobs, completes or fails listener
    jobs, and updates the `SubscriptionRepository`.
  - Nested `UserTaskListenerJobWorker`: owns the Camunda `JobWorker` lifecycle for the configured listener topic.
  - Member extension `TaskSubscriptionHandle.matches(job: ActivatedJob)`: matches listener jobs against task
    subscriptions.
- `TaskInformationExtensions.kt`
  - `ActivatedJob.toUserTaskListenerEvent()`: returns the listener task id and `ListenerEventType`.
  - `ActivatedJob.toUserTaskListenerTaskInformation()`: creates `TaskInformation` metadata for listener jobs.
- `GlobalUserTaskListenerRegistrationHelper.kt`
  - Checks whether global listener auto-registration is enabled.
  - Reads an existing global listener by id.
  - Creates a global listener when it is absent.
  - Leaves existing listeners unchanged when their configuration differs.

There is no standalone `UserTaskListenerEvent` class and no separate top-level `UserTaskListenerJobWorker` file. Those
concepts are represented by `toUserTaskListenerEvent()` and the nested worker class to keep the listener delivery path
local and readable.

## Worker Setup

`ListenerUserTaskDelivery.subscribe()` closes any existing listener worker and opens a new worker with the current
configuration:

```kotlin
camundaClient
  .newWorker()
  .jobType(topic)
  .handler { _, job -> handler(job) }
  .name(workerId)
  .maxJobsActive(maxJobsActive)
  .streamEnabled(streamEnabled)
  .timeout(lockTimeInSeconds * 1000)
  .apply {
    if (fetchVariables != null) {
      fetchVariables(fetchVariables)
    }
  }
  .open()
```

`fetchVariables(...)` is applied only when the adapter can compute a bounded variable projection from registered user task
subscriptions. If any registered user task subscription has `payloadDescription == null`, the worker fetches all
variables. Otherwise it fetches the sorted union of all declared payload variable names. Each delivery still filters
`job.variablesAsMap` with `filterBySubscription(activeSubscription)` before invoking the subscriber action.

The variable projection is calculated when the listener worker opens. If subscriptions are changed later and require a
wider variable set, the worker must be reopened to widen the fetch projection.

Listener jobs are completed without variables:

```kotlin
camundaClient
  .newCompleteCommand(job.key)
  .send()
  .join()
```

The implementation does not call `variables(...)` for listener job completion.

## Event Semantics

`ListenerUserTaskDelivery.consumeActivatedJob(job)` handles only jobs where:

- `job.kind == JobKind.TASK_LISTENER`
- `job.userTask != null`

Non-listener jobs and listener jobs without user task data are logged and completed without delivery.

Supported listener events are mapped to Process Engine API reasons as follows:

| Camunda event | Local task already active | Process Engine API reason | Repository behavior |
|---------------|---------------------------|---------------------------|---------------------|
| `CREATING` | Any state | `TaskInformation.CREATE` | Deliver action, then activate task id for the matching subscription. |
| `ASSIGNING` | No | `TaskInformation.CREATE` | Recover as a create delivery, then activate task id. |
| `ASSIGNING` | Yes | `TaskInformation.ASSIGN` | Deliver action and keep the task active. |
| `UPDATING` | No | `TaskInformation.CREATE` | Recover as a create delivery, then activate task id. |
| `UPDATING` | Yes | `TaskInformation.UPDATE` | Deliver action and keep the task active. |
| `COMPLETING` | Yes | `TaskInformation.COMPLETE` | Complete listener job, deactivate task id, then invoke termination callback. |
| `COMPLETING` | No | None | Complete listener job without a local termination callback. |
| `CANCELING` | Yes | `TaskInformation.DELETE` | Complete listener job, deactivate task id, then invoke termination callback. |
| `CANCELING` | No | None | Complete listener job without a local termination callback. |

Unsupported listener event values are logged and completed without delivery.

The adapter listener is an observer of lifecycle changes. It does not deny lifecycle transitions and does not apply
corrections to the user task.

## Failure Policy

Delivery for `CREATING`, `ASSIGNING`, and `UPDATING` is reliable and blocking:

- Subscriber action success activates the task for the matching subscription and completes the listener job.
- Subscriber action failure fails the listener job with:
  - `retries((job.retries - 1).coerceAtLeast(0))`
  - `retryBackoff(Duration.ofSeconds(retryTimeoutInSeconds))`
  - `errorMessage(...)`

Termination for `COMPLETING` and `CANCELING` does not block the engine lifecycle after the adapter has completed the
listener job. The listener job is completed first. If the termination callback throws, the exception is logged and the task
remains deactivated locally.

## Task Information Metadata

Listener jobs are mapped to `TaskInformation` with the user task key as `taskId`.

The listener mapping includes these metadata entries when the corresponding source value is present:

- Common restriction metadata:
  - `tenantId`
  - `activityId`
  - `executionId`
  - `processDefinitionKey`
  - `processDefinitionId`
  - `processInstanceId`
- Listener metadata:
  - `eventType`
  - `action`
  - `assignee`
  - `candidateUsers`
  - `candidateGroups`
  - `changedAttributes`
  - `dueDate`
  - `followUpDate`
  - `formKey`
  - `priority`
  - `topicName`
  - `retries`
  - `reason`

Collection values such as candidate users, candidate groups, and changed attributes are serialized as comma-separated
strings to match the adapter's existing `TaskInformation` metadata style.

## Subscription Matching

Listener jobs are matched against the first task subscription that satisfies all of these conditions:

- `taskType == TaskType.USER`
- `taskDescriptionKey == null || taskDescriptionKey == job.elementId`
- `job.kind == JobKind.TASK_LISTENER`
- `job.userTask != null`
- all supported restrictions match

Supported listener matching restrictions are:

| Restriction | Listener job source |
|-------------|---------------------|
| `CommonRestrictions.ACTIVITY_ID` | `job.elementId` |
| `CommonRestrictions.EXECUTION_ID` | `job.elementInstanceKey` |
| `CommonRestrictions.TENANT_ID` | `job.tenantId` |
| `CommonRestrictions.PROCESS_INSTANCE_ID` | `job.processInstanceKey` |
| `CommonRestrictions.PROCESS_DEFINITION_ID` | `job.processDefinitionKey` |
| `CommonRestrictions.PROCESS_DEFINITION_KEY` | `job.bpmnProcessId` |

`CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS` is ignored during listener matching. Any other restriction key
causes the subscription not to match.

`unsubscribe(taskSubscription)` removes active delivered user task ids currently associated with that subscription from
the local `SubscriptionRepository`.

## Spring Boot Configuration

The listener strategy is selected with:

```yaml
dev:
  bpm-crafters:
    process-api:
      adapter:
        c8:
          user-tasks:
            delivery-strategy: LISTENER
```

Listener-specific properties live under `user-tasks.listener`:

```yaml
dev:
  bpm-crafters:
    process-api:
      adapter:
        c8:
          user-tasks:
            delivery-strategy: LISTENER
            listener:
              topic: process-engine-user-tasks
              worker-id: process-engine-user-tasks-worker
              max-jobs-active: 32
              stream-enabled: true
              lock-time-in-seconds: 300
              retry-timeout-in-seconds: 5
              auto-register-global-listener: false
              global-listener-id: process-engine-user-tasks
              global-listener-retries: 3
              global-listener-after-non-global: true
              global-listener-priority: 0
```

When `LISTENER` is active:

- `C8CamundaClientAutoConfiguration` creates `ListenerUserTaskDelivery` as bean `c8-user-task-delivery`.
- `C8CamundaClientAutoConfiguration` creates `C8CamundaClientUserTaskCompletionApiImpl` as bean
  `c8-user-task-completion`.
- `C8SubscriptionAutoConfiguration` creates `GlobalUserTaskListenerRegistrationHelper`.
- `C8SubscriptionAutoConfiguration` creates `UserTaskListenerDeliveryBinding` as bean
  `c8-user-task-delivery-subscription`.
- `UserTaskListenerDeliveryBinding` reacts to `ApplicationStartedEvent`, registers the global listener when enabled, and
  then subscribes to listener jobs.

There is no scheduled binding for `LISTENER`. `SCHEDULED` remains the pull-based native user task strategy, and
`SUBSCRIPTION_REFRESHING` remains the Zeebe-job-based user task strategy with job completion.

## Listener Registration

### Global Listener Auto-Registration

Auto-registration is opt-in:

```yaml
dev:
  bpm-crafters:
    process-api:
      adapter:
        c8:
          user-tasks:
            listener:
              auto-register-global-listener: true
```

When enabled, `GlobalUserTaskListenerRegistrationHelper`:

1. Looks up the configured listener id with `newGlobalTaskListenerGetRequest(globalListenerId)`.
2. Creates the listener with `newCreateGlobalTaskListenerRequest()` when the lookup returns 404.
3. Uses event type `GlobalTaskListenerEventType.ALL`.
4. Applies the configured listener type, retries, `afterNonGlobal`, and priority.
5. Leaves an existing listener unchanged when it has different configuration.
6. Throws an `IllegalStateException` when lookup or creation fails for authorization or other non-404 errors.

Static Camunda cluster configuration is still valid when auto-registration is disabled:

```yaml
camunda:
  cluster:
    global-listeners:
      user-task:
        - id: process-engine-user-tasks
          type: process-engine-user-tasks
          event-types: all
          retries: 3
          after-non-global: true
          priority: 0
```

### BPMN-Level Listener Fallback

For Camunda 8.8 compatibility or clusters where global listeners are not desired, define BPMN-level listeners on every
native Camunda user task:

```xml
<zeebe:taskListeners>
  <zeebe:taskListener eventType="creating" type="process-engine-user-tasks" retries="3" />
  <zeebe:taskListener eventType="assigning" type="process-engine-user-tasks" retries="3" />
  <zeebe:taskListener eventType="updating" type="process-engine-user-tasks" retries="3" />
  <zeebe:taskListener eventType="completing" type="process-engine-user-tasks" retries="3" />
  <zeebe:taskListener eventType="canceling" type="process-engine-user-tasks" retries="3" />
</zeebe:taskListeners>
```

Place the adapter listener after business validation or correction listeners for the same event.

## Listener Ordering

User task listeners are blocking. A `COMPLETING` listener fires before the completion lifecycle transition is finalized.
If another listener runs after the adapter listener and denies completion, the adapter can remove the task from its local
state too early.

Mitigation:

- Configure global listeners with `afterNonGlobal = true`.
- Order any global after-non-global listeners that can deny completion before this adapter listener.
- When using BPMN-level listeners, place `process-engine-user-tasks` after validation and correction listeners for the
  same event.

## Tests

Current coverage includes:

- `ListenerUserTaskDeliveryTest`
  - worker opening and fetch-variable projection
  - create delivery and activation
  - assignment delivery
  - update delivery
  - late assignment or update recovery as create
  - completion and cancellation termination
  - subscriber failure retry/fail behavior
  - no matching subscription behavior
  - listener restriction matching
  - non-listener job matching rejection
- `TaskInformationExtensionsTest`
  - listener job event extraction
  - listener `TaskInformation` metadata mapping
- `GlobalUserTaskListenerRegistrationHelperTest`
  - disabled auto-registration
  - existing listener handling
  - absent listener creation
  - registration failure surfacing
- `C8UserTaskDeliveryStrategyAutoConfigurationTest`
  - `LISTENER` bean graph
  - unchanged `SCHEDULED` bean graph
  - unchanged `SUBSCRIPTION_REFRESHING` bean graph

No code in this feature requires `PullUserTaskDelivery.refresh()` for new listener events.

## Known Constraints

- Listener jobs are blocking. Delivery failures can block user task lifecycle transitions and create incidents when
  retries are exhausted.
- `COMPLETING` and `CANCELING` are observed before the lifecycle transition is fully finalized by Camunda.
- In multi-instance applications with in-memory subscription state, a listener job is consumed by only one worker
  instance. Shared task state or application-level broadcasting is required if every node must maintain the same local
  task cache.
- Startup catch-up is not solved by listener events alone. Tasks that already exist before the listener worker starts are
  not discovered unless another bootstrap mechanism performs a native user task search.
- The listener worker's Camunda variable fetch projection is calculated when `subscribe()` opens the worker. Runtime
  subscription changes that require additional variables need a worker reopen to update the projection.

## References

- Camunda 8.8 user task listeners: https://docs.camunda.io/docs/8.8/components/concepts/user-task-listeners/
- Current Camunda user task listener concept: https://docs.camunda.io/docs/components/concepts/user-task-listeners/
- Current global user task listener configuration: https://docs.camunda.io/docs/components/concepts/global-user-task-listeners/configuration/
- Camunda Java client job worker docs: https://docs.camunda.io/docs/apis-tools/java-client/job-worker/
