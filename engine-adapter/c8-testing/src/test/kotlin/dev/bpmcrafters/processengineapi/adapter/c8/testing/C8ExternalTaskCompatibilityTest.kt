package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.testing.config.UseProcessTestInitializer
import dev.bpmcrafters.processengineapi.testing.contract.fixture.ExternalTaskProcessFixture
import dev.bpmcrafters.processengineapi.testing.contract.suite.ExternalTaskCompatibilitySuite

@UseProcessTestInitializer(C8ProcessTestInitializer.QUALIFIER)
class C8ExternalTaskCompatibilityTest : ExternalTaskCompatibilitySuite() {
  override fun externalTaskProcessFixture() = object : ExternalTaskProcessFixture {
    override fun classpathResource() = "contract/c8/external-task.bpmn"
    override fun definitionKey() = "contract-external-task"
    override fun externalTaskDescriptionKey() = "contract-worker"
    override fun endElementId() = "end"
  }
}
