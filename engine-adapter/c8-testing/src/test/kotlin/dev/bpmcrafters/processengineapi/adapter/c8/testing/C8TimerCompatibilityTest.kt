package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.testing.config.UseProcessTestInitializer
import dev.bpmcrafters.processengineapi.testing.contract.fixture.TimerProcessFixture
import dev.bpmcrafters.processengineapi.testing.contract.suite.TimerCompatibilitySuite

@UseProcessTestInitializer(C8ProcessTestInitializer.QUALIFIER)
class C8TimerCompatibilityTest : TimerCompatibilitySuite() {
  override fun timerProcessFixture() = object : TimerProcessFixture {
    override fun classpathResource() = "contract/c8/timer.bpmn"
    override fun definitionKey() = "contract-timer"
    override fun timerElementId() = "timerWait"
    override fun userTaskDescriptionKey() = "approval"
  }
}
