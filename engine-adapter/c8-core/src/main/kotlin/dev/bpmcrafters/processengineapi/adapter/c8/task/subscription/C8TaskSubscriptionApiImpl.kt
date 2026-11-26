package dev.bpmcrafters.processengineapi.adapter.c8.task.subscription

import dev.bpmcrafters.processengineapi.CommonRestrictions
import dev.bpmcrafters.processengineapi.Empty
import dev.bpmcrafters.processengineapi.MetaInfo
import dev.bpmcrafters.processengineapi.MetaInfoAware
import dev.bpmcrafters.processengineapi.adapter.c8.task.SubscribingUserTaskDelivery
import dev.bpmcrafters.processengineapi.impl.task.AbstractTaskSubscriptionApiImpl
import dev.bpmcrafters.processengineapi.impl.task.SubscriptionRepository
import dev.bpmcrafters.processengineapi.task.UnsubscribeFromTaskCmd
import java.util.concurrent.CompletableFuture

class C8TaskSubscriptionApiImpl(
  subscriptionRepository: SubscriptionRepository,
  private val subscribingUserTaskDelivery: SubscribingUserTaskDelivery?
) : AbstractTaskSubscriptionApiImpl(
  subscriptionRepository = subscriptionRepository
) {

  private companion object {
    // The common subset of the C8 external-task and user-task delivery strategies.
    val SUPPORTED_RESTRICTIONS = setOf(
      CommonRestrictions.TENANT_ID,
      CommonRestrictions.PROCESS_INSTANCE_ID,
      CommonRestrictions.PROCESS_DEFINITION_ID,
      CommonRestrictions.PROCESS_DEFINITION_KEY,
      CommonRestrictions.WORKER_LOCK_DURATION_IN_MILLISECONDS
    )
  }

  override fun getSupportedRestrictions(): Set<String> = SUPPORTED_RESTRICTIONS

  override fun meta(instance: MetaInfoAware): MetaInfo {
    TODO("Not yet implemented")
  }

  override fun unsubscribe(cmd: UnsubscribeFromTaskCmd): CompletableFuture<Empty> {
    super.unsubscribe(cmd)
    // For subscribing delivery, we also have to close the job
    subscribingUserTaskDelivery?.unsubscribe(cmd.subscription)
    return CompletableFuture.completedFuture(Empty)
  }

}
