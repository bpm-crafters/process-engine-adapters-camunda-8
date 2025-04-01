## Camunda Platform 8 as SaaS

If you start with a Camunda Platform 8, operated as SaaS, the following configuration is applicable for you.

First add the corresponding adapter to your project's classpath:

```xml 
<dependencies>
    <dependency>
      <groupId>dev.bpm-crafters.process-engine-adapters</groupId>
      <artifactId>process-engine-adapter-camunda-platform-c8-spring-boot-starter</artifactId>
    </dependency>

    <!-- We need the camunda client too -->
    <dependency>
      <groupId>io.camunda</groupId>
      <artifactId>spring-boot-starter-camunda-sdk</artifactId>
      <version>8.6.12</version>
    </dependency>
    <dependency>
      <groupId>io.camunda</groupId>
      <artifactId>camunda-tasklist-client-java</artifactId>
      <version>8.6.12</version>
      <exclusions>
        <exclusion>
          <groupId>io.camunda</groupId>
          <artifactId>zeebe-client-java</artifactId>
        </exclusion>
      </exclusions>
    </dependency>

</dependencies>
```

and configure the adapter in your application properties:

```yaml

dev:
  bpm-crafters:
    process-api:
      adapter:
        c8:
          enabled: true
          service-tasks:
            delivery-strategy: subscription
            worker-id: worker
          user-tasks:
            delivery-strategy: subscription_refreshing
            schedule-delivery-fixed-rate-in-seconds: 5000 # every 5 seconds
            tasklist-url: https://${zeebe.client.cloud.region}.tasklist.camunda.io/${zeebe.client.cloud.clusterId}
            fixed-rate-refresh-rate: 5000 # every 5 seconds

camunda:
  client:
    mode: saas
    region: ${ZEEBE_REGION}
    cluster-id: ${ZEEBE_CLUSTER_ID}
    auth:
      clientId: ${ZEEBE_CLIENT_ID}
      clientSecret: ${ZEEBE_CLIENT_SECRET}

```
