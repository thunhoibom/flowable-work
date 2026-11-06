package io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess;

import io.github.illuseahashmap.workflow.config.SubProcessProperties;
import io.github.illuseahashmap.workflow.process.application.WorkflowInteractionGuard;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.interceptor.StartProcessInstanceAfterContext;
import org.flowable.engine.interceptor.StartProcessInstanceBeforeContext;
import org.flowable.engine.interceptor.StartProcessInstanceInterceptor;
import org.flowable.engine.interceptor.StartSubProcessInstanceAfterContext;
import org.flowable.engine.interceptor.StartSubProcessInstanceBeforeContext;

/**
 * Platform rules for instances started by a call activity, on top of stock Flowable behaviour.
 *
 * <ul>
 *   <li>before: the chain of calling processes may not exceed {@code max-call-depth};</li>
 *   <li>after: the child gets the parent's tenant variables, whatever the in-parameters say, and
 *       its start contract (required Agent inputs) is checked like a start through the API.</li>
 * </ul>
 *
 * Variable rules live in the after hook because Flowable applies {@code <flowable:in>} after the
 * before hook. Failures throw and roll back the whole command, parent included.
 */
public class WorkflowSubProcessStartInterceptor implements StartProcessInstanceInterceptor {

    static final List<String> TENANT_VARIABLES = List.of("tenantId", "tenantCode");

    private final SubProcessProperties properties;
    private final Supplier<WorkflowInteractionGuard> interactionGuard;
    private final StartProcessInstanceInterceptor delegate;

    /**
     * @param interactionGuard resolved lazily: the guard depends on engine services that do not
     *                         exist yet while the engine configuration is being built
     * @param delegate         an interceptor that was configured before this one, or {@code null}
     */
    public WorkflowSubProcessStartInterceptor(SubProcessProperties properties,
                                              Supplier<WorkflowInteractionGuard> interactionGuard,
                                              StartProcessInstanceInterceptor delegate) {
        this.properties = properties;
        this.interactionGuard = interactionGuard;
        this.delegate = delegate;
    }

    @Override
    public void beforeStartProcessInstance(StartProcessInstanceBeforeContext instanceContext) {
        if (delegate != null) {
            delegate.beforeStartProcessInstance(instanceContext);
        }
    }

    @Override
    public void afterStartProcessInstance(StartProcessInstanceAfterContext instanceContext) {
        if (delegate != null) {
            delegate.afterStartProcessInstance(instanceContext);
        }
    }

    @Override
    public void beforeStartSubProcessInstance(StartSubProcessInstanceBeforeContext instanceContext) {
        if (delegate != null) {
            delegate.beforeStartSubProcessInstance(instanceContext);
        }
        int depth = 1;
        ExecutionEntity instance = instanceContext.getCallActivityExecution().getProcessInstance();
        while (instance != null) {
            depth++;
            ExecutionEntity superExecution = instance.getSuperExecution();
            instance = superExecution == null ? null : superExecution.getProcessInstance();
        }
        if (depth > properties.getMaxCallDepth()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Calling " + instanceContext.getProcessDefinition().getKey()
                            + " exceeds the maximum call depth " + properties.getMaxCallDepth());
        }
    }

    @Override
    public void afterStartSubProcessInstance(StartSubProcessInstanceAfterContext instanceContext) {
        ExecutionEntity child = instanceContext.getProcessInstance();
        ExecutionEntity callActivity = instanceContext.getCallActivityExecution();
        for (String name : TENANT_VARIABLES) {
            Object parentValue = callActivity.getVariable(name);
            if (parentValue == null) {
                child.removeVariable(name);
            } else {
                child.setVariable(name, parentValue);
            }
        }
        validateStartContract(instanceContext, child, callActivity);
        if (delegate != null) {
            delegate.afterStartSubProcessInstance(instanceContext);
        }
    }

    private void validateStartContract(StartSubProcessInstanceAfterContext instanceContext,
                                       ExecutionEntity child, ExecutionEntity callActivity) {
        String key = instanceContext.getProcessDefinition().getKey();
        Map<String, Object> variables = new HashMap<>(instanceContext.getVariables());
        TENANT_VARIABLES.forEach(variables::remove);
        Object tenantCode = callActivity.getVariable("tenantCode");
        TenantContext.TenantInfo tenant = new TenantContext.TenantInfo(
                child.getTenantId(), tenantCode == null ? null : tenantCode.toString(), null);
        try {
            TenantContext.callWith(tenant, () -> {
                interactionGuard.get().validateStart(key, instanceContext.getProcessDefinition().getId(), variables);
                return null;
            });
        } catch (BusinessException exception) {
            throw new BusinessException(exception.getErrorCode(),
                    "Quy trình con " + key + ": " + exception.getMessage());
        }
    }
}
