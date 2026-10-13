package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.testing.config.UseProcessTestInitializer
import dev.bpmcrafters.processengineapi.testing.contract.fixture.MessageAndSignalFixture
import dev.bpmcrafters.processengineapi.testing.contract.suite.MessageAndSignalCompatibilitySuite

@UseProcessTestInitializer(C8ProcessTestInitializer.QUALIFIER)
class C8MessageAndSignalCompatibilityTest : MessageAndSignalCompatibilitySuite() {
  override fun messageAndSignalFixture() = object : MessageAndSignalFixture {
    override fun classpathResource() = "contract/c8/message-and-signal.bpmn"
    override fun definitionKey() = "contract-message-and-signal"
    override fun messageName() = "contract.continue"
    override fun correlationVariable() = "correlationKey"
    override fun correlationKey() = "customer-42"
    override fun signalName() = "contract.continue.signal"
    override fun messageCatchElementId() = "messageWait"
    override fun signalCatchElementId() = "signalWait"
    override fun endElementId() = "end"
  }
}
