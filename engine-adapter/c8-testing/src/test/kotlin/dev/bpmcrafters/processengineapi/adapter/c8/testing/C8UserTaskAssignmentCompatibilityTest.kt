package dev.bpmcrafters.processengineapi.adapter.c8.testing

import dev.bpmcrafters.processengineapi.testing.config.UseProcessTestInitializer
import dev.bpmcrafters.processengineapi.testing.contract.fixture.UserTaskAssignmentFixture
import dev.bpmcrafters.processengineapi.testing.contract.suite.UserTaskAssignmentCompatibilitySuite

@UseProcessTestInitializer(C8ProcessTestInitializer.QUALIFIER)
class C8UserTaskAssignmentCompatibilityTest : UserTaskAssignmentCompatibilitySuite() {
  override fun userTaskAssignmentFixture() = object : UserTaskAssignmentFixture {
    override fun classpathResource() = "contract/c8/user-task.bpmn"
    override fun definitionKey() = "contract-user-task"
    override fun userTaskDescriptionKey() = "approval"
    override fun initialAssignee() = "gonzo"
    override fun assignee() = "kermit"
    override fun assigneeMetadataKey() = "assignee"
  }
}
