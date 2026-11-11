package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.task.delivery.SubscribingServiceTaskDelivery
import io.camunda.client.CamundaClient
import io.camunda.client.api.worker.JobWorker
import io.quarkus.test.QuarkusUnitTest
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.Config
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID

object ClientLifecycleTestSupport {
  fun application(supplied: Boolean = false, fail: Boolean = false): QuarkusUnitTest {
    // The runtime application and after-undeploy callback use different class loaders.
    val events = Path.of(System.getProperty("java.io.tmpdir"), "c8-client-${UUID.randomUUID()}.events")
    val expectedClose = if (supplied) "application-client-closed" else "default-client-closed"
    val application = QuarkusUnitTest()
      .withApplicationRoot { archive ->
        archive.addClasses(ClientLifecycleTestSupport::class.java, ClientLifecycleProbe::class.java, LifecycleDeliveryProducer::class.java)
        if (supplied) archive.addClass(ApplicationOwnedClientProducer::class.java)
      }
      .overrideConfigKey("quarkus.process-engine-api.c8.enabled", "true")
      .overrideConfigKey("quarkus.process-engine-api.c8.service-tasks.delivery-strategy", "SUBSCRIPTION")
      .overrideConfigKey("quarkus.process-engine-api.c8.user-tasks.delivery-strategy", "CUSTOM")
      .overrideRuntimeConfigKey("quarkus.process-engine-api.c8.service-tasks.worker-id", "lifecycle-test")
      .overrideRuntimeConfigKey("test.c8.events-file", events.toString())
      .overrideRuntimeConfigKey("test.c8.supplied-client", supplied.toString())
      .overrideRuntimeConfigKey("test.c8.fail-startup", fail.toString())
      .setBeforeAllCustomizer { Files.createFile(events) }
      .setAfterUndeployListener {
        assertThat(Files.readAllLines(events)).containsExactly(
          "client-created", "worker-opened", "worker-closed", expectedClose
        )
      }
      .setAfterAllCustomizer { Files.deleteIfExists(events) }
    if (fail) {
      application.assertException { error ->
        val original = generateSequence(error) { it.cause }.firstOrNull { it.message == "worker-startup-failure" }
        assertThat(original).`as`("original worker startup failure").isNotNull()
        assertThat(original!!.suppressed).hasSize(1)
        assertThat(original).hasStackTraceContaining("worker-close-failure")
      }
    }
    return application
  }
}

@Singleton
class ClientLifecycleProbe @Inject constructor(config: Config) {
  private val events = Path.of(config.getValue("test.c8.events-file", String::class.java))
  val supplied = config.getValue("test.c8.supplied-client", Boolean::class.java)
  val fail = config.getValue("test.c8.fail-startup", Boolean::class.java)

  @Synchronized
  fun record(event: String) {
    Files.writeString(events, "$event\n", APPEND)
  }

  fun observeDefaultClient(client: CamundaClient) {
    record("client-created")
    // Version-pinned SDK probe: observe real close() without replacing its CDI producer or opening network workers.
    val closeables = client.javaClass.getDeclaredField("closeables").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val resources = closeables.get(client) as MutableList<Closeable>
    resources.add(Closeable { record("default-client-closed") })
  }
}

@Singleton
class LifecycleDeliveryProducer {
  @Produces
  @Singleton
  fun delivery(client: CamundaClient, probe: ClientLifecycleProbe): SubscribingServiceTaskDelivery {
    if (!probe.supplied) probe.observeDefaultClient(client)
    val worker = mock<JobWorker>()
    doAnswer { probe.record("worker-closed"); null }.whenever(worker).close()
    val delivery = mock<SubscribingServiceTaskDelivery>()
    doAnswer {
      probe.record("worker-opened")
      if (probe.fail) throw IllegalStateException("worker-startup-failure")
      null
    }.whenever(delivery).subscribe()
    doAnswer {
      worker.close()
      if (probe.fail) throw IllegalStateException("worker-close-failure")
      null
    }.whenever(delivery).close()
    return delivery
  }
}

@Singleton
class ApplicationOwnedClientProducer {
  @Produces
  @Singleton
  fun client(probe: ClientLifecycleProbe): CamundaClient {
    probe.record("client-created")
    val client = mock<CamundaClient>()
    doAnswer { probe.record("application-client-closed"); null }.whenever(client).close()
    return client
  }

  fun close(@Disposes client: CamundaClient) {
    verify(client, never()).close()
    client.close()
  }
}
