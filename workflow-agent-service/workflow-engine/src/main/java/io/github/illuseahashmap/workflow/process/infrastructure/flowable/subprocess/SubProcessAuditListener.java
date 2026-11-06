package io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess;

import io.github.illuseahashmap.workflow.process.application.port.WorkflowOperationAuditRepository;
import io.github.illuseahashmap.workflow.process.domain.WorkflowOperationAudit;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.CurrentTraceContext;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records when a call activity starts or completes a child instance, with its parent and root.
 *
 * <p>Only runs inside a user or service request. In an async job there is no principal and no
 * tenant session for row-level security, and a failed audit insert must never break the process.
 * Terminations are audited by the terminate command, which knows the reason.
 */
public class SubProcessAuditListener implements FlowableEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubProcessAuditListener.class);
    static final Set<FlowableEngineEventType> TYPES = Set.of(
            FlowableEngineEventType.PROCESS_STARTED,
            FlowableEngineEventType.PROCESS_COMPLETED,
            FlowableEngineEventType.PROCESS_COMPLETED_WITH_ERROR_END_EVENT);

    private final Supplier<WorkflowOperationAuditRepository> repository;

    public SubProcessAuditListener(Supplier<WorkflowOperationAuditRepository> repository) {
        this.repository = repository;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (!(event.getType() instanceof FlowableEngineEventType type) || !TYPES.contains(type)
                || !(event instanceof FlowableEntityEvent entityEvent)
                || !(entityEvent.getEntity() instanceof ExecutionEntity execution)) {
            return;
        }
        // PROCESS_STARTED from a call activity carries the child's first execution, not the instance.
        ExecutionEntity instance = execution.isProcessInstanceType() ? execution : execution.getProcessInstance();
        if (instance == null || instance.getSuperExecutionId() == null) {
            return;
        }
        CurrentPrincipal actor = CurrentPrincipalContext.currentOrNull();
        if (actor == null) {
            LOGGER.debug("Skipping subprocess audit for {} outside a request", instance.getId());
            return;
        }
        String eventType = switch (type) {
            case PROCESS_STARTED -> "SUBPROCESS_STARTED";
            case PROCESS_COMPLETED_WITH_ERROR_END_EVENT -> "SUBPROCESS_FAILED";
            default -> "SUBPROCESS_COMPLETED";
        };
        String nextState = switch (type) {
            case PROCESS_STARTED -> "RUNNING";
            case PROCESS_COMPLETED_WITH_ERROR_END_EVENT -> "FAILED";
            default -> "COMPLETED";
        };
        String parentId = instance.getSuperExecution() == null
                ? null : instance.getSuperExecution().getProcessInstanceId();
        repository.get().record(new WorkflowOperationAudit(
                eventType,
                actor.tenantCode(),
                actor.principalType(),
                actor.principalId(),
                actor.username(),
                instance.getId(),
                instance.getProcessDefinitionKey(),
                null,
                "parent=" + parentId + ", root=" + instance.getRootProcessInstanceId(),
                type == FlowableEngineEventType.PROCESS_STARTED ? null : "RUNNING",
                nextState,
                null,
                CurrentTraceContext.currentOrNull(),
                Instant.now()));
    }

    @Override
    public boolean isFailOnException() {
        return false;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }
}
