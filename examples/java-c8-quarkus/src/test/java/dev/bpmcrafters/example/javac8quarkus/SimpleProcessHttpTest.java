package dev.bpmcrafters.example.javac8quarkus;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(CamundaTestResource.class)
public class SimpleProcessHttpTest extends SimpleProcessHttpScenarios {}
