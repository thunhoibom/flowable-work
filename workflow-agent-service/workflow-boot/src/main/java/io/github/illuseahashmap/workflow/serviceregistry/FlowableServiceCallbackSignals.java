package io.github.illuseahashmap.workflow.serviceregistry;

import java.util.Map;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.bpmn.helper.ErrorPropagation;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.springframework.stereotype.Component;

@Component
public class FlowableServiceCallbackSignals implements ServiceCallbackSignals {

    private final RuntimeService runtimeService;
    private final ManagementService managementService;

    public FlowableServiceCallbackSignals(RuntimeService runtimeService, ManagementService managementService) {
        this.runtimeService = runtimeService;
        this.managementService = managementService;
    }

    @Override
    public void trigger(String executionId, Map<String, Object> variables) {
        runtimeService.trigger(executionId, variables == null ? Map.of() : variables);
    }

    @Override
    public void fail(String executionId) {
        managementService.executeCommand(context -> {
            ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager(context).findById(executionId);
            if (execution != null) {
                // The wait ended without a matching callback.
                ErrorPropagation.propagateError("SERVICE_UNAVAILABLE", execution);
            }
            return null;
        });
    }
}
