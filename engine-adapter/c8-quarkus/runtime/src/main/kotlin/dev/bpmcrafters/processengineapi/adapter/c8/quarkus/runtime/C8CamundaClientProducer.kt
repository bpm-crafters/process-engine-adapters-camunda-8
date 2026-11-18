package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import io.camunda.client.CamundaClient
import io.camunda.client.CredentialsProvider
import io.quarkus.arc.DefaultBean
import io.quarkus.runtime.configuration.DurationConverter
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.Config
import java.net.URI
import java.time.Duration

@Singleton
class C8CamundaClientProducer @Inject constructor(
  private val config: C8AdapterRuntimeConfig,
  private val source: Config
) {

  @Produces
  @Singleton
  @DefaultBean
  fun client(): CamundaClient {
    val client = config.client()
    val grpc = endpoint(client.grpcAddress().orElse(""), "grpc-address")
    val rest = endpoint(client.restAddress().orElse(""), "rest-address", allowPath = true)
    val timeout = requestTimeout(client.requestTimeout().orElse(""))
    val preferRest = preferRestOverGrpc(client.preferRestOverGrpc().orElse(""))
    val oauth = client.oauth()
    // Optional mapping treats explicit empty values as absent; still reject configured empty credentials.
    val selected = listOf("client-id", "client-secret", "token-audience").any {
      source.getConfigValue("quarkus.process-engine-api.c8.client.oauth.$it").rawValue != null
    }
    val credentials = if (selected) {
      valid(oauth.clientId().filter { it.isNotBlank() }.isPresent, "oauth.client-id")
      valid(oauth.clientSecret().filter { it.isNotBlank() }.isPresent, "oauth.client-secret")
      valid(oauth.tokenAudience().filter { it.isNotBlank() }.isPresent, "oauth.token-audience")
      val authUrl = endpoint(oauth.authUrl().orElse(""), "oauth.auth-url", allowPath = true)
      CredentialsProvider.newCredentialsProviderBuilder()
        .applyEnvironmentOverrides(false)
        .clientId(oauth.clientId().get())
        .clientSecret(oauth.clientSecret().get())
        .audience(oauth.tokenAudience().get())
        .authorizationServerUrl(authUrl.toString())
        .build()
    } else null
    val builder = CamundaClient.newClientBuilder()
      .applyEnvironmentVariableOverrides(false)
      .grpcAddress(grpc)
      .restAddress(rest)
      .preferRestOverGrpc(preferRest)
      .defaultRequestTimeout(timeout)
    if (credentials != null) builder.credentialsProvider(credentials)
    return builder.build()
  }

  // CDI invokes this only for this producer's product, never for an application producer.
  fun close(@Disposes client: CamundaClient) = client.close()

  private fun requestTimeout(value: String): Duration {
    val timeout = try { DurationConverter.parseDuration(value) } catch (_: RuntimeException) { null }
    valid(timeout != null && !timeout.isNegative && !timeout.isZero &&
      timeout <= Duration.ofMillis(Long.MAX_VALUE), "request-timeout")
    return timeout!!
  }

  private fun preferRestOverGrpc(value: String): Boolean = when (value.trim().lowercase(java.util.Locale.ROOT)) {
    "true", "1", "yes", "y", "on" -> true
    "false", "0", "no", "n", "off" -> false
    else -> throw IllegalArgumentException("quarkus.process-engine-api.c8.client.prefer-rest-over-grpc is missing or invalid")
  }

  private fun endpoint(value: String, property: String, allowPath: Boolean = false): URI {
    val uri = try { URI(value) } catch (_: Exception) { null }
    valid(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
      uri.userInfo == null && uri.query == null && uri.fragment == null &&
      (uri.port == -1 || uri.port in 1..65535) && (allowPath || uri.path.isNullOrEmpty() || uri.path == "/"), property)
    return uri!!
  }

  private fun valid(condition: Boolean, property: String) {
    require(condition) { "quarkus.process-engine-api.c8.client.$property is missing or invalid" }
  }
}
