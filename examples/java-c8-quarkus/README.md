# Java Camunda 8 Quarkus example

Run the Process Engine API with Java 17, Quarkus `3.40.1.1`, Quarkiverse Camunda `2.1.3`, and Camunda `8.10.7` on the JVM. This application uses CDI and JAX-RS, not Spring or the Spring-coupled common fixture.

Maven packages [the existing Spring Boot example model](../java-c8-sb3/src/main/resources/simple-process.bpmn) unchanged at the classpath root. The [diagram](../java-c8-sb3/src/main/resources/simple-process.png) describes both examples.

## Run locally

From the repository root, build/install the unreleased adapter and example:

```shell
./mvnw -pl examples/java-c8-quarkus -am clean install
docker compose -f examples/java-c8-quarkus/docker-compose.yaml up -d
# Wait until http://localhost:8088/v2/topology reports a healthy leader.
java -Dquarkus.profile=run -jar examples/java-c8-quarkus/target/quarkus-app/quarkus-run.jar
```

Docker is also required for the build's real-engine tests. The application binds to loopback port `18083`, alongside the Spring Boot 3 example on `18082`. Override `CAMUNDA_GRPC_ADDRESS` and `CAMUNDA_REST_ADDRESS` to connect to another local server. Both endpoints must point to the same cluster.

Open [Swagger UI](http://localhost:18083/q/swagger-ui) or use [simple-process-demo.http](simple-process-demo.http). OpenAPI is available at `/q/openapi`. The HTTP file generates UUIDs locally in the IDE; it does not call an external UUID service or busy-wait.

The Compose stack is **local-only**: APIs are unauthenticated, UI credentials are `demo` / `demo`, and database credentials are `camunda` / `camunda`. Only loopback ports `8088` and `26500` are exposed; PostgreSQL has no host port. Do not use this configuration in production. Access [Operate](http://localhost:8088/operate), [Tasklist](http://localhost:8088/tasklist), and [Admin](http://localhost:8088/admin). Stop the disposable stack with:

```shell
docker compose -f examples/java-c8-quarkus/docker-compose.yaml down -v
```

## Walk through the process

All routes below start with `/simple-service-tasks`.

| Request | Result |
| --- | --- |
| `POST /deploy` | Deploy `simple-process.bpmn`; `201`, deployment key in `Location`, empty body |
| `POST /start-process?value=<unique-key>&intValue=123` | Start `simple-process-1`; `201`, instance ID in `Location`, empty body |
| `GET /tasks` | Listener-delivered `TaskInformation` list; `200` |
| `POST /tasks/<taskId>/complete?value=user-task-value` | Complete native user task with `some-user-value`; `204` |
| `POST /correlate/<unique-key>?value=message-value` | Correlate `message1`, set `message-delivered-value`; `204` |
| `POST /signal?value=signal-value` | Broadcast `signal1`, set `signal-delivered-value`; `204` |
| `POST /tasks/<taskId>/error?value=task-error-value` | Explicitly unsupported native user-task BPMN error; `501`, task stays active |

Missing/blank values and invalid 32-bit `intValue` parameters return `400`.

1. Deploy, start with `123`, and list tasks. The automatic `execute-action-external` worker copies all variables and adds `action1=value1`.
2. Complete the user task **before one minute passes**. Its assignee is `kermit`, candidate group `avengers`, and candidate users `gonzo` and `fozzy`.
3. The automatic `send-message-external` worker adds `action2=value2` (it does not send real email). Correlate using the initial `value`; the process ends at `finished`.
4. Start with a multiple of `42`. The first worker reports BPMN `action_error`. Wait for `received_signal`, then broadcast a signal; the process ends at `finished_abnormally`.
5. Start with `123`, leave the user task untouched, and wait for its unchanged `PT1M` timer. The listener removes the canceled task from the cache. Broadcast a signal after `received_signal` becomes active to finish abnormally.

The initial payload contains `stringValue`, integer `intValue`, `listVariable=[element1,element2]`, `listIntVariable=[12,13,15]`, `toBeOrNotToBe=true`, and the Java record `complex={foo:foo,bar:bar}`. Technical worker exceptions call `failTask` with decremented engine retries (or the adapter's default retry supplier when metadata is absent), eventually creating an incident. There is no fault-injection production endpoint.

The native user-task error route deliberately differs from a successful completion: Camunda's core adapter does not support throwing BPMN errors for native user-task keys. `501` is this example's explicit HTTP convention, not a claim about the Camunda REST API. Do not use job-based error commands with native user-task keys.

Subscriptions register at CDI startup priority `2500`, before adapter delivery at `3000`. Services use `SUBSCRIPTION`; native user tasks use `LISTENER`, global listener registration and preload, with the default five-second interval. The adapter owns workers; Quarkiverse owns the shared client. The application unregisters after adapter workers close. See the [extension lifecycle limitations](../../docs/quarkus.md#known-upstream-limitations).

## SaaS

Use the Console-provided endpoints and credentials, not the local Compose configuration:

```shell
export CAMUNDA_GRPC_ADDRESS='https://<cluster-id>.<region>.zeebe.camunda.io:443'
export CAMUNDA_REST_ADDRESS='https://<region>.api.camunda.io/<cluster-id>'
export ZEEBE_CLIENT_ID='<client-id>'
export ZEEBE_CLIENT_SECRET='<client-secret>'
java -Dquarkus.profile=saas -jar examples/java-c8-quarkus/target/quarkus-app/quarkus-run.jar
```

The REST address is a base URL **without `/v2`**. Both addresses are mandatory; the Quarkiverse `cloud.*` shortcut configures only gRPC in this pinned version. OAuth defaults to `https://login.cloud.camunda.io/oauth/token` and audience `zeebe.camunda.io`; override `CAMUNDA_OAUTH_URL` / `CAMUNDA_TOKEN_AUDIENCE` with the Console values (migrated credentials may use `api.camunda.io`). Never commit secrets.

Global listener registration reads the listener and creates it when missing. Grant the required `GLOBAL_LISTENER` / `CREATE_TASK_LISTENER` permission as well as ordinary deployment, process, and task permissions. Registration failure stops startup; this example does not silently fall back to polling. **Live SaaS is not validated without credentials and listener permissions.** See [SaaS client connections](https://docs.camunda.io/docs/apis-tools/java-client/getting-started/#step-2b-configure-the-orchestration-cluster-connection-for-saas) and [SaaS architecture migration](https://docs.camunda.io/docs/apis-tools/migration-manuals/saas-orchestration-architecture/).

## Tests

```shell
./mvnw -pl examples/java-c8-quarkus -am clean verify -B -ntp
```

Unit tests inspect API commands, payloads, worker branches and subscription cleanup. HTTP tests run the actual application, listener, and both workers against Camunda `8.10.7` in Testcontainers. They verify happy completion, service BPMN error, retry exhaustion/incident, and the real one-minute timer. The packaged JVM integration test runs the same HTTP suite. Tests use an independent test-owned client for engine observations; they add no application debug endpoints. No native build or SaaS credentials are required. This runnable example demonstrates Spring Boot 3 feature parity; the extension's separate integration application covers additional APIs and all delivery strategies.
