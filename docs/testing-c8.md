---
title: Camunda 8 testing
---

# Camunda 8 testing

Use `process-engine-adapter-camunda-platform-c8-process-testing` with qualifier `c8`. The
initializer starts Camunda Process Test's default container runtime, so a working container runtime
is required. It closes the runtime after each scenario.

The provider supports the common process-testing operations and time travel. Deployment visibility
is eventually consistent; the default one-second propagation delay can be changed with annotation
configuration key `c8.deployment-propagation-delay` (an ISO-8601 `Duration`) or system property
`bpmcrafters.c8.testing.deployment-propagation-delay`.
