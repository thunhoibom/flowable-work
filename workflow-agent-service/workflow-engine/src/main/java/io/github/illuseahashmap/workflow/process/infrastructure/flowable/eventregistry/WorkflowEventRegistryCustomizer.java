package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;

/** Every change the platform makes to the event registry engine, applied before it is built. */
public final class WorkflowEventRegistryCustomizer {

    private WorkflowEventRegistryCustomizer() {
    }

    public static void customize(EventRegistryEngineConfiguration configuration,
                                 RestInboundChannelModelProcessor restChannels) {
        configuration.getChannelJsonConverter().addInboundChannelModelClass("rest", RestInboundChannelModel.class);
        if (!configuration.getChannelModelProcessors().contains(restChannels)) {
            configuration.addChannelModelProcessor(restChannels);
        }
        configuration.setNonMatchingEventConsumer(new WorkflowNonMatchingEventConsumer());
        // Tenant comes from the caller's identity only (R-C4).
        configuration.setFallbackToDefaultTenant(false);
    }
}
