package dev.bpmcrafters.example.javac8quarkus;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@QuarkusTestResource(CamundaTestResource.class)
public class SimpleProcessHttpIT extends SimpleProcessHttpScenarios {}
