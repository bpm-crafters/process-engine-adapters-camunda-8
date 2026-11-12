package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.testing.config.UseProcessTestInitializer
import dev.bpmcrafters.processengineapi.testing.contract.fixture.AlternateStartFixture
import dev.bpmcrafters.processengineapi.testing.contract.suite.AlternateStartCompatibilitySuite

@UseProcessTestInitializer(C8ProcessTestInitializer.QUALIFIER)
class C8AlternateStartCompatibilityTest : AlternateStartCompatibilitySuite() {
  override fun alternateStartFixture() = object : AlternateStartFixture {
    override fun classpathResource() = "contract/c8/alternate-starts.bpmn"
    override fun definitionKey() = "contract-alternate-starts"
    override fun startMessageName() = "contract.start"
    override fun entryElementId() = "approval"
    override fun userTaskDescriptionKey() = "approval"
  }
}
