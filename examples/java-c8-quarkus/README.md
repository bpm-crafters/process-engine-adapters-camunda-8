# Java Camunda 8 Quarkus example

## Run locally

From the repository root:

```shell
./mvnw -pl examples/java-c8-quarkus -am clean install
docker compose -f examples/java-c8-quarkus/docker-compose.yaml up -d
# Wait until http://localhost:8088/v2/topology reports a healthy leader.
java -Dquarkus.profile=run -jar examples/java-c8-quarkus/target/quarkus-app/quarkus-run.jar
```

Open [Swagger UI](http://localhost:18083/q/swagger-ui) or use [simple-process-demo.http](simple-process-demo.http). OpenAPI is available at `/q/openapi`.

The Compose stack is **local-only**: APIs are unauthenticated, UI credentials are `demo` / `demo`, and database credentials are `camunda` / `camunda`. Only loopback ports `8088` and `26500` are exposed. PostgreSQL has no host port. Do not use this configuration in production. Access [Operate](http://localhost:8088/operate), [Tasklist](http://localhost:8088/tasklist), and [Admin](http://localhost:8088/admin). Stop the disposable stack with:

```shell
docker compose -f examples/java-c8-quarkus/docker-compose.yaml down -v
```
