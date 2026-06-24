---
title: Camunda Platform 8 Reference
---

# Delivery modes and their impact

The adapter delivers service tasks and user tasks through independent strategies. The selected strategy decides which
Camunda API is used, which completion API is wired, how quickly task changes are visible, and which metadata is
available in `TaskInformation.getMeta()`.

## Service Tasks

| Strategy | Implementation | Impact |
|----------|----------------|--------|
| `SUBSCRIPTION` | Opens Camunda job workers for `TaskType.EXTERNAL` subscriptions. The subscription `taskDescriptionKey` is used as the job type. | Service tasks are pushed by Camunda jobs. Payload variables are fetched according to the subscription `payloadDescription`. `workerLockDurationInMilliseconds` can override the configured worker lock time per subscription. |
| `CUSTOM` | No built-in service task delivery bean is created. | Provide your own delivery implementation. The default service task completion API is still available unless you replace it with your own bean. |

## User Tasks

| Strategy | Implementation                                                                                                | Impact                                                                                                                                                                                                                                                                                                                                             |
|----------|---------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SCHEDULED` | Periodically searches Camunda user tasks with state `CREATED`.                                                | Uses the Camunda user task search and variable search APIs. Task delivery is delayed by `schedule-delivery-fixed-rate-in-seconds`. A task is delivered with reason `create` the first time and `update` when delivered again to the same subscription. The Camunda client is fetching tasks from the secondary storage, so some delay is expected. |
| `SUBSCRIPTION_REFRESHING` | Opens Zeebe job workers for the Legacy Camunda user task job type and refreshes the job timeout periodically. | User tasks are delivered as jobs and remain locked until completed or the lock refresh detects that the job is gone. Completion uses the job completion API. If timeout refresh returns `NOT_FOUND`, the subscription termination handler receives reason `delete`.                                                                                |
| `LISTENER` | Opens a worker for Camunda user task listener jobs.                                                           | User task changes are delivered from task listener events. Completion uses the Camunda user task completion API.                                                                                                                                                                                                                                   |
| `CUSTOM` | No built-in user task delivery or completion bean is created.                                                 | Provide your own delivery and completion implementation.                                                                                                                                                                                                                                                                                           |

The listener strategy requires user task listener jobs with the configured topic. You can define these listeners in BPMN
or enable global listener auto-registration. Auto-registration feature is supported and uses Camunda's Orchestration Cluster API and will fail
startup if the API call fails.

# Configuration Overview

Spring Boot configuration is rooted at `dev.bpm-crafters.process-api.adapter.c8`. The adapter autoconfiguration is only
active when `enabled` is explicitly set to `true`.

```yaml
dev:
  bpm-crafters:
    process-api:
      adapter:
        c8:
          enabled: true
          service-tasks:
            delivery-strategy: SUBSCRIPTION
            worker-id: worker
            retries: 3
            retry-timeout-in-seconds: 5
            lock-time-in-seconds: 300
          user-tasks:
            delivery-strategy: LISTENER
            schedule-delivery-fixed-rate-in-seconds: 5
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

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | Must be set | Enables the C8 adapter autoconfiguration. Although the properties class defaults to `true`, the condition requires this property to be explicitly bound. |
| `service-tasks.delivery-strategy` | Required | `SUBSCRIPTION` or `CUSTOM`. |
| `service-tasks.worker-id` | Required | Worker name used for service task job workers and for `SUBSCRIPTION_REFRESHING` user task workers. |
| `service-tasks.retries` | `3` | Default retries used by the failure retry supplier. |
| `service-tasks.retry-timeout-in-seconds` | `5` | Backoff used when failing service task jobs after handler errors. |
| `service-tasks.lock-time-in-seconds` | `300` | Default service task worker lock time. Can be overridden by `workerLockDurationInMilliseconds` on a service task subscription. |
| `user-tasks.delivery-strategy` | Required | `SCHEDULED`, `SUBSCRIPTION_REFRESHING`, `LISTENER`, or `CUSTOM`. |
| `user-tasks.schedule-delivery-fixed-rate-in-seconds` | `5` | Polling interval for `SCHEDULED` delivery and timeout refresh interval for `SUBSCRIPTION_REFRESHING`. |
| `user-tasks.listener.topic` | `process-engine-user-tasks` | Camunda user task listener job type consumed by the `LISTENER` strategy. |
| `user-tasks.listener.worker-id` | `process-engine-user-tasks-worker` | Worker name used by the listener job worker. |
| `user-tasks.listener.max-jobs-active` | `32` | Maximum active listener jobs. |
| `user-tasks.listener.stream-enabled` | `true` | Enables Camunda job streaming for listener jobs. |
| `user-tasks.listener.lock-time-in-seconds` | `300` | Listener job lock time. |
| `user-tasks.listener.retry-timeout-in-seconds` | `5` | Backoff used when failing listener jobs after handler errors. |
| `user-tasks.listener.auto-register-global-listener` | `false` | If `true`, the adapter registers a global user task listener on startup. |
| `user-tasks.listener.global-listener-id` | `process-engine-user-tasks` | Id used for global listener auto-registration. |
| `user-tasks.listener.global-listener-retries` | `3` | Retry count configured for the global listener. |
| `user-tasks.listener.global-listener-after-non-global` | `true` | Configures the global listener to run after BPMN-level listeners. |
| `user-tasks.listener.global-listener-priority` | `0` | Priority configured for the global listener. |

# Message Correlation

Correlation API implementation supports the following restrictions:

| Key | Value | Description |
|-----|-------|-------------|
| `tenantId` | Camunda tenant id | Passed to the Camunda publish-message command with `tenantId(value)`. Empty values are ignored. |

All other restriction keys are rejected before the message command is sent. Message correlation uses the command's
`messageName`, the `correlationKey` from `cmd.correlation.get().correlationKey`, and the variables from
`cmd.payloadSupplier.get()`.

# Task Subscription Restrictions

Task subscription restrictions are evaluated by the delivery strategy when a task is considered for delivery. Unknown
restriction keys are not ignored; they make the task fail matching for that subscription. The
`workerLockDurationInMilliseconds` restriction is special: it is applied as a lock-duration override for service task
workers and ignored during task matching.

The subscription `taskDescriptionKey` is matched outside the restriction map. For service tasks it matches the Camunda
job type. For user tasks it matches the BPMN user task element id (`elementId`). A `null` `taskDescriptionKey` matches
any task of the requested task type. Payload delivery follows the API contract: `payloadDescription == null` delivers all
available variables, an empty set delivers no variables, and a non-empty set delivers only the named variables.

| Task type and strategy | Supported matching keys |
|------------------------|-------------------------|
| Service tasks, `SUBSCRIPTION` | `activityId`, `executionId`, `tenantId`, `processInstanceId`, `processDefinitionId` |
| User tasks, `SCHEDULED` | `tenantId`, `processInstanceId`, `processDefinitionId`, `processDefinitionKey` |
| User tasks, `SUBSCRIPTION_REFRESHING` | `activityId`, `executionId`, `tenantId`, `processInstanceId`, `processDefinitionId`, `processDefinitionKey` |
| User tasks, `LISTENER` | `activityId`, `executionId`, `tenantId`, `processInstanceId`, `processDefinitionId`, `processDefinitionKey` |

# Task Information

The `TaskInformation.getMeta()` provides meta information about the task as `Map<String, String>` for maximum
compatibility. The Original Type column denotes the source value type before the adapter serializes it into the meta map.
For typed access, `TaskInformation` provides `getMetaValueAsOffsetDate`, `getMetaValueAsStringSet`, and
`getMetaValueAsInt`.

Meta entries with `null` values are usually omitted. One current exception is scheduled user task `formId`, which is
created with `toString()` and can therefore contain the literal string `null` if no form key is available.

Every delivery can add `reason` to the meta map by calling `TaskInformation.withReason(...)`. Known reason values are
`create`, `assign`, `update`, `complete`, and `delete`.

## User Tasks

The following table combines the user task metadata from all built-in user task delivery strategies. Legend:
❎ = supported by the strategy, 0️⃣ = not provided by the strategy.

| Key | `SCHEDULED` | `SUBSCRIPTION_REFRESHING` | `LISTENER` | Original Type | Description | Example |
|-----|-------------|----------------------------|------------|---------------|-------------|---------|
| `activityId` | ❎ | ❎ | ❎ | `String` | BPMN user task element id. | `approve-request` |
| `executionId` | 0️⃣ | 0️⃣ | ❎ | `Long` | Camunda element instance key. | `2251799813685311` |
| `processDefinitionKey` | ❎ | ❎ | ❎ | `String` | BPMN process id. | `invoice-process` |
| `processDefinitionId` | ❎ | ❎ | ❎ | `Long` | Camunda process definition key. | `2251799813685250` |
| `processInstanceId` | ❎ | ❎ | ❎ | `Long` | Camunda process instance key. | `2251799813685301` |
| `tenantId` | ❎ | ❎ | ❎ | `String` | Camunda tenant id. | `tenant-a` |
| `assignee` | ❎ | ❎ | ❎ | `String` | Current assignee. For `SUBSCRIPTION_REFRESHING`, this comes from Camunda custom headers. | `alice` |
| `candidateUsers` | ❎ | ❎ | ❎ | `Collection<String>` or `String` | Candidate users, serialized as comma-separated values. For `SUBSCRIPTION_REFRESHING`, this is the raw custom header value. | `alice,bob` |
| `candidateGroups` | ❎ | ❎ | ❎ | `Collection<String>` or `String` | Candidate groups, serialized as comma-separated values. For `SUBSCRIPTION_REFRESHING`, this is the raw custom header value. | `accounting,ops` |
| `followUpDate` | ❎ | ❎ | ❎ | `OffsetDateTime` or `String` | Follow-up date. For `SUBSCRIPTION_REFRESHING`, this comes from Camunda custom headers. | `2026-06-23T10:15:30Z` |
| `dueDate` | ❎ | ❎ | ❎ | `OffsetDateTime` or `String` | Due date. For `SUBSCRIPTION_REFRESHING`, this comes from Camunda custom headers. | `2026-06-24T10:15:30Z` |
| `creationDate` | ❎ | 0️⃣ | 0️⃣ | `OffsetDateTime` | User task creation date. | `2026-06-23T09:00:00Z` |
| `processName` | ❎ | 0️⃣ | 0️⃣ | `String` | Process display name returned by Camunda. | `Invoice Process` |
| `taskName` | ❎ | 0️⃣ | 0️⃣ | `String` | User task display name returned by Camunda. | `Approve request` |
| `formId` | ❎ | 0️⃣ | 0️⃣ | `String` | Value returned by `Form.formKey`. Can be the literal string `null`. | `camunda-forms:bpmn:approve` |
| `formKey` | ❎ | ❎ | ❎ | `String` | Form reference. `SCHEDULED` maps `Form.formId`; `SUBSCRIPTION_REFRESHING` maps the form key custom header; `LISTENER` maps the listener user task form key. | `approve-form` |
| `formVersion` | ❎ | 0️⃣ | 0️⃣ | `Long` | Form version returned by Camunda. | `3` |
| `taskState` | ❎ | 0️⃣ | 0️⃣ | `UserTaskState` | Camunda user task state. | `CREATED` |
| `topicName` | 0️⃣ | ❎ | ❎ | `String` | Activated job type or listener job type. | `process-engine-user-tasks` |
| `retries` | 0️⃣ | ❎ | ❎ | `Int` | Activated job retry count. | `3` |
| `eventType` | 0️⃣ | 0️⃣ | ❎ | `ListenerEventType` | Camunda listener event type. | `CREATING` |
| `action` | 0️⃣ | 0️⃣ | ❎ | `String` | User task action from the listener job. | `assign` |
| `changedAttributes` | 0️⃣ | 0️⃣ | ❎ | `Collection<String>` | Changed user task attributes, serialized as comma-separated values. | `assignee,priority` |
| `priority` | 0️⃣ | 0️⃣ | ❎ | `Int` | User task priority. | `80` |
| `reason` | ❎ | ❎ | ❎ | `String` | Delivery or termination reason added by the adapter. | `create` |

## Service Tasks

These values are mapped from Camunda activated jobs in the `SUBSCRIPTION` strategy.

| Key | Original Type | Description | Example |
|-----|---------------|-------------|---------|
| `activityId` | `String` | BPMN service task element id. | `charge-card` |
| `processDefinitionKey` | `String` | BPMN process id. | `payment-process` |
| `processDefinitionId` | `Long` | Camunda process definition key. | `2251799813685250` |
| `processInstanceId` | `Long` | Camunda process instance key. | `2251799813685301` |
| `tenantId` | `String` | Camunda tenant id. | `tenant-a` |
| `formKey` | `String` | Custom header using Camunda's user task form key header name, if present. | `camunda-forms:bpmn:approve` |
| `assignee` | `String` | Custom header using Camunda's user task assignee header name, if present. | `alice` |
| `dueDate` | `String` | Custom header using Camunda's user task due date header name, if present. | `2026-06-24T10:15:30Z` |
| `candidateUsers` | `String` | Custom header using Camunda's user task candidate users header name, if present. Use `getMetaValueAsStringSet` when the value is comma-separated. | `alice,bob` |
| `candidateGroups` | `String` | Custom header using Camunda's user task candidate groups header name, if present. Use `getMetaValueAsStringSet` when the value is comma-separated. | `accounting,ops` |
| `followUpDate` | `String` | Custom header using Camunda's user task follow-up date header name, if present. | `2026-06-23T10:15:30Z` |
| `topicName` | `String` | Activated job type. | `charge-card` |
| `retries` | `Int` | Activated job retry count. | `3` |
| `reason` | `String` | Delivery or termination reason added by the adapter. | `create` |
