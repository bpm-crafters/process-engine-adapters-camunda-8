# ADR 0001: Delay message-start contract execution after deployment

## Status

Proposed

## Context

`process-engine-api-testing-contract` contains `AlternateStartCompatibilitySuite`. Its
message-start scenario deploys a BPMN model and immediately publishes the start message through
`StartProcessByMessageCmd`.

Camunda 8 creates a message subscription for every message start event when a process is deployed.
The deployment response can arrive before that subscription is available to correlate a published
message. A message published during that interval is not correlated and no process instance is
created. A message TTL does not solve this condition: Camunda only buffers a message with a TTL
when an already-active process instance prevents creation for the same correlation key.

Camunda's `PublishMessageResponse` does not directly include the started process-instance key.
It does include the published message key, which can be used to search correlated message
subscriptions and obtain their process-instance key.

The Camunda Java client supports message-start creation directly. The correct call is:

```kotlin
client.newPublishMessageCommand()
  .messageName(messageName)
  .withoutCorrelationKey()
  .variables(payload)
  .send()
```

An empty correlation key (`correlationKey("")`) must not be used as a substitute for omitting the
key. `withoutCorrelationKey()` represents the API command's absence of
`CommonRestrictions.CORRELATION_KEY` and is the client operation intended for message start
events.

## Decision

The C8 process-test context should decorate its `DeploymentApi`. After the delegate's deployment
future completes successfully, the decorator waits for the agreed short, fixed delay before
completing its own future. All contract deployment steps therefore receive the delay without a
change to the engine-neutral contract suites.

The delay is configured with the ISO-8601 duration system property
`bpmcrafters.c8.testing.deployment-propagation-delay`; it defaults to `PT1S`.
An individual test class can override the delay through the shared initializer annotation, for
example `@UseProcessTestInitializer(qualifier = "c8", configuration =
["c8.deployment-propagation-delay=PT2S"])`. Annotation configuration takes precedence over the
system property.

The delay belongs after each deployment, not in C8 runtime setup. In particular,
`runtime.waitUntilClusterReady(...)` only establishes cluster readiness; it does not ensure a
specific newly deployed model's message-start subscription is correlatable.

The production `DeploymentApiImpl` remains unchanged. This is a testing-adapter-only timing
workaround, not a readiness guarantee.

`StartProcessApiImpl` resolves the process instance for a message start by polling correlated
message subscriptions with the message key returned by the publication response. The lookup is
enabled by default and returns the correlated process-instance key. It can be disabled with a
negative timeout, or configured through its timeout and maximum-timeout settings.

## Consequences

- Every C8 process-test deployment waits briefly before the next scenario action executes.
- The production C8 adapter continues to publish starts with `withoutCorrelationKey()`.
- The fixed delay reduces flakiness but cannot guarantee that a subscription exists; a future
  observable readiness API could replace it if one becomes available.
- Message-start callers receive the instance id created by their published message.

## References

- [Camunda message events](https://docs.camunda.io/docs/components/modeler/bpmn/message-events/)
  documents message-start subscriptions, correlation, and the behavior of messages published before
  deployment.
