package dev.bpmcrafters.example.javac8quarkus;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import java.util.Map;

public final class ExampleTestData {
  private ExampleTestData() {}

  public static TaskInformation task(final Integer retries) {
    return new TaskInformation("123", retries == null ? Map.of() : Map.of(TaskInformation.RETRIES, retries.toString()));
  }

  public static Map<String, Object> payload(final Object number) {
    return Map.of("intValue", number, "stringValue", "run-123", "nested", Map.of("keep", true));
  }
}
