package dev.bpmcrafters.processengineapi.adapter.c8.quarkus.deployment

import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8AdapterBuildTimeConfig
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8AdapterLifecycle
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8AdapterProducer
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8CamundaClientProducer
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8ListenerTaskProducer
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8NativeUserTaskProducer
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8RefreshingTaskProducer
import dev.bpmcrafters.processengineapi.adapter.c8.quarkus.runtime.C8ServiceTaskProducer
import io.quarkus.arc.deployment.AdditionalBeanBuildItem
import io.quarkus.deployment.annotations.BuildStep
import io.quarkus.deployment.builditem.FeatureBuildItem

class C8AdapterProcessor {

  @BuildStep
  fun feature(): FeatureBuildItem = FeatureBuildItem("process-engine-api-c8")

  @BuildStep
  fun adapterBeans(config: C8AdapterBuildTimeConfig): AdditionalBeanBuildItem? {
    if (!config.enabled()) return null
    config.validate()
    val beans = AdditionalBeanBuildItem.builder().setUnremovable()
      .addBeanClasses(C8AdapterProducer::class.java, C8AdapterLifecycle::class.java, C8CamundaClientProducer::class.java)
    if (config.serviceTasks().deliveryStrategy().orElseThrow() == C8AdapterBuildTimeConfig.ServiceStrategy.SUBSCRIPTION) {
      beans.addBeanClass(C8ServiceTaskProducer::class.java)
    }
    when (config.userTasks().deliveryStrategy().orElseThrow()) {
      C8AdapterBuildTimeConfig.UserStrategy.SCHEDULED -> beans.addBeanClass(C8NativeUserTaskProducer::class.java)
      C8AdapterBuildTimeConfig.UserStrategy.SUBSCRIPTION_REFRESHING -> beans.addBeanClass(C8RefreshingTaskProducer::class.java)
      C8AdapterBuildTimeConfig.UserStrategy.LISTENER ->
        beans.addBeanClasses(C8NativeUserTaskProducer::class.java, C8ListenerTaskProducer::class.java)
      C8AdapterBuildTimeConfig.UserStrategy.CUSTOM -> Unit // Application owns user task delivery and completion.
    }
    return beans.build()
  }
}
