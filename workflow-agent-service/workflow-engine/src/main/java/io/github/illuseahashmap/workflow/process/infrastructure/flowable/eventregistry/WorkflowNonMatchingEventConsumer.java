package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import org.flowable.eventregistry.api.EventRegistryEvent;
import org.flowable.eventregistry.api.EventRegistryNonMatchingEventConsumer;
import org.flowable.eventregistry.api.EventRegistryProcessingInfo;
import org.flowable.eventregistry.api.runtime.EventInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Called by Flowable when no consumer handled an event. Marks the dispatch context (R-A1). */
public class WorkflowNonMatchingEventConsumer implements EventRegistryNonMatchingEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowNonMatchingEventConsumer.class);

    @Override
    public void handleNonMatchingEvent(EventRegistryEvent event, EventRegistryProcessingInfo processingInfo) {
        String key = event.getEventObject() instanceof EventInstance instance ? instance.getEventKey() : event.getType();
        LOGGER.info("No waiting instance matched event {}", key);
        InboundDispatchContext.current().ifPresent(InboundDispatchContext::markNoMatch);
    }
}
