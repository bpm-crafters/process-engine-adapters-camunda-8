package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import io.camunda.client.CamundaClient
import io.camunda.client.impl.oauth.OAuthCredentialsProvider
import io.camunda.client.impl.util.Environment
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.net.URI
import java.time.Duration

class C8CamundaClientProducerTest {
  private fun producer(values: Map<String, String> = emptyMap()): C8CamundaClientProducer {
    val config = ConfigTestData.configuration(values)
    return C8CamundaClientProducer(config.runtime, config.source)
  }

  @Test
  fun `creates official client with local unauthenticated defaults`() {
    producer().client().use { client ->
      assertThat(client.configuration.grpcAddress).isEqualTo(URI("http://localhost:26500"))
      assertThat(client.configuration.restAddress).isEqualTo(URI("http://localhost:8080"))
      assertThat(client.configuration.preferRestOverGrpc()).isTrue()
      assertThat(client.configuration.defaultRequestTimeout).isEqualTo(Duration.ofSeconds(45))
      assertThat(client.configuration.credentialsProvider).isNull()
    }
  }

  @Test
  fun `maps endpoints transport and timeout including SaaS base path`() {
    producer(mapOf(
      "client.grpc-address" to "https://grpc.example:443/",
      "client.rest-address" to "https://rest.example/cluster-id",
      "client.prefer-rest-over-grpc" to "false",
      "client.request-timeout" to "PT12S"
    )).client().use { client ->
      assertThat(client.configuration.grpcAddress).isEqualTo(URI("https://grpc.example:443/"))
      assertThat(client.configuration.restAddress).isEqualTo(URI("https://rest.example/cluster-id"))
      assertThat(client.configuration.preferRestOverGrpc()).isFalse()
      assertThat(client.configuration.defaultRequestTimeout).isEqualTo(Duration.ofSeconds(12))
    }
  }

  @Test
  fun `maps OAuth without fetching tokens or accepting SDK environment overrides`() {
    val environment = Environment.system()
    val overrides = mapOf(
      "CAMUNDA_REST_ADDRESS" to "http://ignored.example:9999",
      "CAMUNDA_GRPC_ADDRESS" to "http://ignored.example:9998",
      "CAMUNDA_CLIENT_ID" to "ignored-id",
      "CAMUNDA_CLIENT_SECRET" to "ignored-secret",
      "CAMUNDA_AUTHORIZATION_SERVER_URL" to "https://ignored.example/token",
      "CAMUNDA_TOKEN_AUDIENCE" to "ignored-audience"
    )
    val previous = overrides.mapValues { (key, _) -> environment.get(key) }
    try {
      overrides.forEach { (key, value) -> environment.put(key, value) }
      producer().client().use { client ->
        assertThat(client.configuration.grpcAddress.host).isEqualTo("localhost")
        assertThat(client.configuration.restAddress.host).isEqualTo("localhost")
        assertThat(client.configuration.credentialsProvider).isNull()
      }
      producer(mapOf(
        "client.oauth.client-id" to "configured-id",
        "client.oauth.client-secret" to "configured-secret",
        "client.oauth.token-audience" to "configured-audience",
        "client.oauth.auth-url" to "https://auth.example/token"
      )).client().use { client ->
        val credentials = client.configuration.credentialsProvider
        assertThat(credentials).isInstanceOf(OAuthCredentialsProvider::class.java)
          .extracting("clientId", "clientSecret", "audience", "authorizationServerUrl")
          .containsExactly("configured-id", "configured-secret", "configured-audience", java.net.URL("https://auth.example/token"))
      }
    } finally {
      previous.forEach { (key, value) -> if (value == null) environment.remove(key) else environment.put(key, value) }
    }
  }

  @Test
  fun `rejects invalid endpoints and timeout with property diagnostics only`() {
    val cases = listOf(
      "client.grpc-address" to "not an endpoint secret-value",
      "client.grpc-address" to "ftp://localhost:26500",
      "client.grpc-address" to "http://localhost:26500/path",
      "client.grpc-address" to "http:///secret-value",
      "client.grpc-address" to "https:///secret-value",
      "client.grpc-address" to "http://localhost:0",
      "client.rest-address" to "http://secret-value@localhost:8080",
      "client.rest-address" to "http://localhost:8080?secret-value",
      "client.rest-address" to "http://localhost:8080#secret-value",
      "client.rest-address" to "http://localhost:99999",
      "client.grpc-address" to "",
      "client.rest-address" to "",
      "client.request-timeout" to "PT0S",
      "client.request-timeout" to "PT-1S",
      "client.request-timeout" to Duration.ofMillis(Long.MAX_VALUE).plusMillis(1).toString(),
      "client.request-timeout" to "not-a-duration-secret-value",
      "client.prefer-rest-over-grpc" to "not-a-boolean-secret-value",
      "client.request-timeout" to "",
      "client.prefer-rest-over-grpc" to ""
    )
    cases.forEach { (property, value) ->
      assertThatThrownBy { producer(mapOf(property to value)).client() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessage("quarkus.process-engine-api.c8.$property is missing or invalid")
        .hasMessageNotContaining("secret-value")
        .hasNoCause()
    }
  }

  @Test
  fun `retains Quarkus duration shorthand and explicit boolean conversions`() {
    for ((value, expected) in mapOf("12" to Duration.ofSeconds(12), "2m" to Duration.ofMinutes(2), "25ms" to Duration.ofMillis(25))) {
      producer(mapOf("client.request-timeout" to value)).client().use {
        assertThat(it.configuration.defaultRequestTimeout).isEqualTo(expected)
      }
    }
    for ((value, expected) in mapOf("TRUE" to true, "yes" to true, "1" to true, "FALSE" to false, "off" to false, "0" to false)) {
      producer(mapOf("client.prefer-rest-over-grpc" to value)).client().use {
        assertThat(it.configuration.preferRestOverGrpc()).isEqualTo(expected)
      }
    }
  }

  @Test
  fun `rejects incomplete or blank OAuth credentials without exposing secrets`() {
    val valid = mapOf(
      "client.oauth.client-id" to "test-id",
      "client.oauth.client-secret" to "secret-value",
      "client.oauth.token-audience" to "test-audience"
    )
    for (property in valid.keys) {
      for (values in listOf(valid - property, valid + (property to " "))) {
        assertThatThrownBy { producer(values).client() }
          .hasMessage("quarkus.process-engine-api.c8.$property is missing or invalid")
          .hasMessageNotContaining("secret-value")
      }
    }
    assertThatThrownBy { producer(valid + ("client.oauth.auth-url" to "invalid secret-value")).client() }
      .hasMessage("quarkus.process-engine-api.c8.client.oauth.auth-url is missing or invalid")
    assertThatThrownBy { producer(valid + ("client.oauth.auth-url" to "")).client() }
      .hasMessage("quarkus.process-engine-api.c8.client.oauth.auth-url is missing or invalid")
    for (blank in listOf("", " ")) {
      assertThatThrownBy { producer(mapOf(
        "client.oauth.client-id" to blank, "client.oauth.client-secret" to blank
      )).client() }.hasMessage("quarkus.process-engine-api.c8.client.oauth.client-id is missing or invalid")
    }
  }

  @Test
  fun `disposes the producer product`() {
    val owned = mock<CamundaClient>()
    producer().close(owned)
    verify(owned).close()
  }
}
