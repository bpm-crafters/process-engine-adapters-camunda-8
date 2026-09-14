package dev.bpmcrafters.processengineapi.adapter.c8.quarkus

import jakarta.inject.Qualifier

/**
 * Marks the pull delivery used for the one-shot startup preload of the listener strategy. Without
 * it the preload delivery and the delivery of the `SCHEDULED` strategy would be the same bean type,
 * and the fixed rate refresh would pick up the preload one. The Spring Boot starter separates the
 * two by bean name (`c8-user-task-listener-preload-delivery`).
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.FIELD,
  AnnotationTarget.VALUE_PARAMETER
)
annotation class ListenerPreload
