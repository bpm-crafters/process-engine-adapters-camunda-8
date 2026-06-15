# User Task Listener Delivery Strategy Plan

## Context

The current native Camunda user task delivery strategy is pull-based. `PullUserTaskDelivery` searches for user tasks in `UserTaskState.CREATED`, delivers matching tasks to the local `SubscriptionRepository`, and marks already delivered tasks as updates. This breaks down when a task disappears outside this adapter, for example when it is completed through Tasklist, completed through another client, canceled by process termination, or interrupted by BPMN flow. Those removals are not visible in the next `CREATED` search result, so the local delivered-task state can become stale.

Camunda user task listeners are a better fit for this regression because they publish blocking job-worker jobs for user task lifecycle events. The listener worker can observe `creating`, `assigning`, `updating`, `completing`, and `canceling` events and update the Process Engine API subscription state from those events.

The listener job topic/type for this adapter must be:

```text
process-engine-user-tasks
```

Camunda calls this value the listener `type` or job type. This plan treats it as the task topic.

## Target Outcome

- Add a worker that handles Camunda user task listener jobs for `process-engine-user-tasks`.
- Add a new native user task delivery strategy based on this worker.
- Preserve existing Process Engine API subscription semantics: matching by task type, task description key, restrictions, payload description, action callback, and termination callback.
- Stop relying on periodic `CREATED` polling as the source of truth for task removals.
- Keep the existing `SCHEDULED` and `SUBSCRIPTION_REFRESHING` strategies available while introducing the listener strategy as an explicit opt-in.

## Version Guard

- Camunda 8.8 documents BPMN-level user task listeners.
- The current project uses `camunda.version` 8.9.6, and the local 8.9.6 Java client exposes global task listener APIs such as `newCreateGlobalTaskListenerRequest`.
- The local 8.8.21 Java client sources do not expose global task listener APIs. If this feature must support 8.8.x, use BPMN-level listener definitions there and guard any global listener auto-registration behind an 8.9+ capability check.

## Design

### New Core Types

Add these classes under `engine-adapter/c8-core/src/main/kotlin/dev/bpmcrafters/processengineapi/adapter/c8/task/delivery`:

- `UserTaskListenerJobWorker`: owns Camunda `JobWorker` lifecycle for topic `process-engine-user-tasks`.
- `UserTaskListenerDelivery`: implements the Process Engine API delivery behavior on top of listener events and implements `SubscribingUserTaskDelivery`.
- `UserTaskListenerEvent`: internal immutable event model created from `ActivatedJob`.

Add mapping helpers in `TaskInformationExtensions.kt` or a sibling file:

- `ActivatedJob.toUserTaskListenerEvent()`
- `ActivatedJob.toUserTaskListenerTaskInformation()`
- `TaskSubscriptionHandle.matchesUserTaskListener(job: ActivatedJob)`

### Listener Job Handling

The worker should open one listener job worker:

```kotlin
camundaClient
  .newWorker()
  .jobType("process-engine-user-tasks")
  .handler { _, job -> handle(job) }
  .name(workerId)
  .streamEnabled(true)
  .maxJobsActive(maxJobsActive)
  .open()
```

Use the 8.9.6 client fields that are available on `ActivatedJob`:

- `job.kind == JobKind.TASK_LISTENER`
- `job.listenerEventType`
- `job.userTask.userTaskKey`
- `job.userTask.action`
- `job.userTask.assignee`
- `job.userTask.candidateUsers`
- `job.userTask.candidateGroups`
- `job.userTask.changedAttributes`
- `job.userTask.dueDate`
- `job.userTask.followUpDate`
- `job.userTask.formKey`
- `job.userTask.priority`
- existing job metadata: `elementId`, `elementInstanceKey`, `processInstanceKey`, `processDefinitionKey`, `bpmnProcessId`, `tenantId`, `variablesAsMap`

Complete listener jobs without variables:

```kotlin
camundaClient
  .newCompleteCommand(job.key)
  .send()
  .join()
```

Do not use `variables(...)` when completing listener jobs. Camunda documents listener job completion with variables as unsupported.

### Event Semantics

Map listener events to Process Engine API delivery state like this:

- `CREATING`: deliver `TaskInformation.CREATE`, activate the matching subscription for `userTaskKey`.
- `ASSIGNING`: deliver `TaskInformation.UPDATE` if the task is already active locally; otherwise deliver `CREATE` to recover from missed startup events.
- `UPDATING`: deliver `TaskInformation.UPDATE` if the task is already active locally; otherwise deliver `CREATE`.
- `COMPLETING`: complete the listener job successfully, then deactivate the active subscription and call `termination` with `TaskInformation.COMPLETE`.
- `CANCELING`: complete the listener job successfully, then deactivate the active subscription and call `termination` with `TaskInformation.DELETE`.

The adapter listener should never deny lifecycle transitions and should not apply corrections. It is a delivery observer, not a business validation listener.

### Listener Ordering

User task listeners are blocking. A `COMPLETING` listener fires before the completion lifecycle transition is finalized. If another listener runs after the adapter listener and denies completion, the adapter may remove the task too early.

Mitigation:

- For global listener registration, set `afterNonGlobal = true` and `priority = 0`.
- Document that `process-engine-user-tasks` should be the last listener for `completing` when using BPMN-level listeners.
- If a project uses other global after-non-global listeners that may deny completion, that project must order them before this adapter listener or accept the early-removal risk.

### Failure Policy

Use reliable delivery as the first implementation:

- If mapping fails or the subscriber action throws for `CREATING`, `ASSIGNING`, or `UPDATING`, fail the listener job with decremented retries and a retry backoff. This keeps event delivery at-least-once, but it can block the user task lifecycle and eventually create an incident.
- If the termination callback throws for `COMPLETING` or `CANCELING`, log the error after the listener job is completed. The engine lifecycle should not be blocked after the task is already leaving active state.
- Add a follow-up option only if needed: `fail-on-delivery-error: false` for observer mode, where the listener job is always completed and delivery errors are only logged.

### Payload Handling

Use `job.variablesAsMap` for the initial implementation and filter it with `filterBySubscription(activeSubscription)`.

When opening the worker:

- If all active user task subscriptions have bounded `payloadDescription` values, fetch the union of those variable names.
- If any active subscription has `payloadDescription == null`, do not restrict fetched variables.
- If a subscription has `payloadDescription == emptySet()`, deliver an empty payload to that subscription after filtering.

This preserves current subscription behavior while avoiding a variable search request per event.

### Subscription Matching

Match listener jobs to subscriptions using the existing pattern:

- `taskType == TaskType.USER`
- `taskDescriptionKey == null || taskDescriptionKey == job.elementId`
- restrictions:
  - `CommonRestrictions.ACTIVITY_ID` -> `job.elementId`
  - `CommonRestrictions.EXECUTION_ID` -> `job.elementInstanceKey`
  - `CommonRestrictions.TENANT_ID` -> `job.tenantId`
  - `CommonRestrictions.PROCESS_INSTANCE_ID` -> `job.processInstanceKey`
  - `CommonRestrictions.PROCESS_DEFINITION_ID` -> `job.processDefinitionKey`
  - `CommonRestrictions.PROCESS_DEFINITION_KEY` -> `job.bpmnProcessId`
- ignore `CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS` for listener matching.

Preserve current behavior by delivering a listener event to the first matching user task subscription.

## Spring Boot Configuration

Extend `C8AdapterProperties.UserTaskDeliveryStrategy`:

```kotlin
enum class UserTaskDeliveryStrategy {
  SCHEDULED,
  SUBSCRIPTION_REFRESHING,
  LISTENER,
  CUSTOM
}
```

Extend `C8AdapterProperties.UserTasks` with listener-specific properties:

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
```

Wire `LISTENER` in `C8CamundaClientAutoConfiguration`:

- create `UserTaskListenerDelivery` as bean `c8-user-task-delivery`.
- use `C8CamundaClientUserTaskCompletionApiImpl` for user task completion, because this strategy targets native Camunda user tasks.
- do not use `C8CamundaClientUserTaskJobCompletionApiImpl`; that class is for job-based user tasks.

Wire startup in `C8SubscriptionAutoConfiguration`:

- add a listener-specific binding, for example `UserTaskListenerDeliveryBinding`.
- on `ApplicationStartedEvent`, call `userTaskListenerDelivery.subscribe()` or `open()`.
- on shutdown, close the underlying `JobWorker`.

No scheduled binding is needed for this strategy.

## Listener Registration Options

### Preferred For Camunda 8.9+

Use global task listener registration so BPMN models do not need modification.

Static cluster configuration:

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

Optional auto-registration can be added after the worker is stable:

```kotlin
camundaClient
  .newCreateGlobalTaskListenerRequest()
  .id("process-engine-user-tasks")
  .type("process-engine-user-tasks")
  .eventTypes(GlobalTaskListenerEventType.ALL)
  .afterNonGlobal()
  .priority(0)
  .retries(3)
  .send()
  .join()
```

Make auto-registration opt-in. It changes cluster state and needs Orchestration Cluster API permissions.

### Camunda 8.8 Compatible Fallback

Use BPMN-level listeners on every native Camunda user task:

```xml
<zeebe:taskListeners>
  <zeebe:taskListener eventType="creating" type="process-engine-user-tasks" retries="3" />
  <zeebe:taskListener eventType="assigning" type="process-engine-user-tasks" retries="3" />
  <zeebe:taskListener eventType="updating" type="process-engine-user-tasks" retries="3" />
  <zeebe:taskListener eventType="completing" type="process-engine-user-tasks" retries="3" />
  <zeebe:taskListener eventType="canceling" type="process-engine-user-tasks" retries="3" />
</zeebe:taskListeners>
```

Place these listeners after any business validation/correction listeners for the same event.

## Implementation Milestones

### 1. Worker Spike

- Add a minimal `UserTaskListenerJobWorker` that opens a worker for `process-engine-user-tasks`.
- Validate that jobs with `JobKind.TASK_LISTENER` expose `listenerEventType` and `userTask`.
- Complete listener jobs without variables.
- Add unit tests with mocked `CamundaClient`, worker builder, and `ActivatedJob`.

Exit criteria:

- A listener job can be consumed and completed.
- Non-task-listener jobs are ignored or failed explicitly with a clear log message.

### 2. Event Mapping

- Add `UserTaskListenerEvent` and mapping helpers.
- Add listener task metadata to `TaskInformation`, including:
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
  - process and tenant restrictions already used elsewhere.
- Add unit tests for complete metadata mapping and null handling.

Exit criteria:

- Mapping covers all listener user task properties exposed by the 8.9.6 Java client.
- No listener completion command carries variables.

### 3. Delivery Strategy

- Add `UserTaskListenerDelivery`.
- Reuse `SubscriptionRepository` for active task tracking.
- Implement subscription matching for listener jobs.
- Implement event-to-reason behavior for create, update, complete, and delete.
- Implement `unsubscribe(taskSubscription)` by removing active delivered tasks for that subscription if the repository API permits it; otherwise document the repository limitation and keep unsubscribe equivalent to existing behavior.
- Add unit tests for:
  - create delivery and activation.
  - update delivery.
  - late update recovering as create.
  - complete termination and deactivation.
  - cancel termination and deactivation.
  - no matching subscription.
  - subscriber failure retry/fail behavior.

Exit criteria:

- No periodic `PullUserTaskDelivery.refresh()` is required for new listener events.
- Completed and canceled tasks are removed from local active-delivery state.

### 4. Spring Boot Integration

- Add `LISTENER` to `UserTaskDeliveryStrategy`.
- Add listener properties with defaults.
- Create listener delivery and native completion beans under `LISTENER`.
- Add `UserTaskListenerDeliveryBinding` to start/stop the worker.
- Ensure `SCHEDULED` and `SUBSCRIPTION_REFRESHING` remain unchanged.
- Add configuration tests for:
  - `LISTENER` creates listener delivery and native user task completion.
  - `SCHEDULED` still creates pull delivery.
  - `SUBSCRIPTION_REFRESHING` still creates job-based user task delivery and completion.

Exit criteria:

- Applications can enable the strategy with `delivery-strategy: LISTENER`.
- Existing strategies retain their current bean graph.

### 5. Listener Registration Support

- Document BPMN-level listener XML for Camunda 8.8 compatibility.
- Document global listener configuration for Camunda 8.9+.
- Add optional global auto-registration only after the manual/global configuration path is tested.
- If auto-registration is implemented:
  - search/get existing listener by id first.
  - create it if absent.
  - update only when explicitly configured to do so.
  - surface authorization failures clearly at startup.

Exit criteria:

- Users can choose manual BPMN-level registration, static global configuration, or opt-in API registration.
- The default behavior does not mutate cluster listener configuration.

### 6. Regression Tests

- Add a BPMN test resource with a native user task and `process-engine-user-tasks` listeners.
- Add integration tests using Camunda Process Test:
  - starting a process delivers the user task without scheduled polling.
  - completing the user task through `newCompleteUserTaskCommand` removes it from local support.
  - canceling or interrupting the task removes it from local support.
  - assigning/updating a task produces update delivery.
- Keep the existing pull-strategy tests and examples intact.

Exit criteria:

- The original stale-task failure is covered: a task removed outside the adapter triggers the termination callback.
- Tests demonstrate that listener delivery does not depend on `UserTaskState.CREATED` search loops.

## Rollout Plan

1. Ship the listener worker and strategy behind `delivery-strategy: LISTENER`.
2. Keep `SCHEDULED` as the default until the listener strategy has integration coverage against both local process tests and a real orchestration cluster.
3. Update examples to include one listener-based profile, for example `application-listener.yml`.
4. Add docs explaining:
   - the required topic/type `process-engine-user-tasks`.
   - BPMN-level listener setup for 8.8.
   - global listener setup for 8.9+.
   - listener ordering requirements.
   - multi-instance constraints.
5. After adoption, consider deprecating `SCHEDULED` for native user task delivery but keep it available as a compatibility fallback.

## Risks And Decisions

- Listener jobs are blocking. Delivery failures can block user task lifecycle transitions and create incidents if retries are exhausted.
- `COMPLETING` is observed before final completion. The adapter listener should be last for completion events to avoid removing a task that a later listener denies.
- Global listener APIs are available in the current 8.9.6 client, not in the local 8.8.21 client sources.
- In multi-instance applications with in-memory subscription state, a listener job is consumed by only one worker instance. Shared task state or an application-level broadcast is required if every node must maintain the same local task cache.
- Startup catch-up is not solved by listener events alone. If tasks already exist before this worker starts, add a one-time native user task search on startup as a bootstrap step, not a periodic pull loop.

## References

- Camunda 8.8 user task listeners: https://docs.camunda.io/docs/8.8/components/concepts/user-task-listeners/
- Current Camunda user task listener concept: https://docs.camunda.io/docs/components/concepts/user-task-listeners/
- Current global user task listener configuration: https://docs.camunda.io/docs/components/concepts/global-user-task-listeners/configuration/
- Camunda Java client job worker docs: https://docs.camunda.io/docs/apis-tools/java-client/job-worker/
