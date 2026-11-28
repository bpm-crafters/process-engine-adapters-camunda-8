package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.it;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@QuarkusTestResource(CamundaTestResource.class)
public class ProcessResourceIT extends ProcessResourceTest {
}
