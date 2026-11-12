package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

class ExtensionMetadataTest {

  @Test
  fun `generated metadata declares minimum Quarkus and application Java versions`() {
    val properties = Properties().apply {
      requireNotNull(ExtensionMetadataTest::class.java.getResourceAsStream("/META-INF/quarkus-extension.properties")).use { load(it) }
    }
    val yaml = requireNotNull(javaClass.getResourceAsStream("/META-INF/quarkus-extension.yaml"))
      .bufferedReader().use { it.readText() }

    assertThat(properties.getProperty("requires-quarkus-version")).isEqualTo("[3.37.3,)")
    assertThat(yaml).contains("requires-quarkus-core: \"[3.37.3,)\"", "minimum-java-version: \"17\"")
  }
}
