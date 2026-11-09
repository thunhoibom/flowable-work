package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import java.util.Collection;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.eventregistry.BpmnEventRegistryEventConsumer;
import org.flowable.eventregistry.api.EventConsumerInfo;
import org.flowable.eventregistry.api.EventRegistryProcessingInfo;
import org.flowable.eventregistry.api.runtime.EventInstance;
import org.flowable.eventregistry.impl.consumer.CorrelationKey;
import org.flowable.eventsubscription.api.EventSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flowable's BPMN consumer with one change: a failing subscription no longer stops the loop over
 * the remaining ones (R-D2). Matching and triggering are inherited unchanged. Each trigger is its
 * own Flowable command, so a failure rolls back only that execution, as long as no outer
 * transaction is open.
 */
public class WorkflowBpmnEventRegistryEventConsumer extends BpmnEventRegistryEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowBpmnEventRegistryEventConsumer.class);

    public WorkflowBpmnEventRegistryEventConsumer(ProcessEngineConfigurationImpl processEngineConfiguration) {
        super(processEngineConfiguration);
    }

    /**
     * The base class reads the command executor in its constructor. This consumer is created while
     * the engine is being configured, before that executor exists, so resolve it on first use.
     */
    @Override
    protected EventRegistryProcessingInfo eventReceived(EventInstance eventInstance) {
        if (commandExecutor == null) {
            commandExecutor = processEngineConfiguration.getCommandExecutor();
        }
        return super.eventReceived(eventInstance);
    }

    @Override
    protected void handleEventSubscription(RuntimeService runtimeService, EventSubscription eventSubscription,
            EventInstance eventInstance, Collection<CorrelationKey> correlationKeys,
            EventConsumerInfo eventConsumerInfo) {
        try {
            super.handleEventSubscription(runtimeService, eventSubscription, eventInstance, correlationKeys,
                    eventConsumerInfo);
            InboundDispatchContext.current().ifPresent(context -> context.recordSuccess(
                    eventSubscription.getProcessInstanceId(), eventSubscription.getActivityId()));
        } catch (RuntimeException exception) {
            String code = exception instanceof FlowableOptimisticLockingException
                    ? "CONCURRENT_UPDATE" : "EVENT_PROCESSING_FAILED";
            // No payload or correlation values in the log.
            LOGGER.warn("Event {} failed for subscription {} (process instance {}, activity {}): {}",
                    eventInstance.getEventKey(), eventSubscription.getId(),
                    eventSubscription.getProcessInstanceId(), eventSubscription.getActivityId(), code, exception);
            InboundDispatchContext.current().ifPresent(context -> context.recordFailure(
                    eventSubscription.getProcessInstanceId(), eventSubscription.getActivityId(), code));
        }
    }
}
