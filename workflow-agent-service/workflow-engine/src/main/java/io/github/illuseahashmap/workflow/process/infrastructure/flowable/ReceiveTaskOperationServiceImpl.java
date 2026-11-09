package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.process.application.ReceiveTaskOperationService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessWaitView;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ActivityInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;

/**
 * Lists and continues manual wait steps. Only a receive task without an Agent binding and without
 * an event type can be continued here (R-F1): the others are resumed by their own machinery.
 */
@Service
public class ReceiveTaskOperationServiceImpl implements ReceiveTaskOperationService {

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final ProcessInstanceTransactionExecutor transactionExecutor;
    private final TenantProvider tenantProvider;
    private final WorkflowOperationAuditService auditService;

    public ReceiveTaskOperationServiceImpl(RuntimeService runtimeService, RepositoryService repositoryService,
                                           HistoryService historyService,
                                           ProcessInstanceTransactionExecutor transactionExecutor,
                                           TenantProvider tenantProvider, WorkflowOperationAuditService auditService) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.transactionExecutor = transactionExecutor;
        this.tenantProvider = tenantProvider;
        this.auditService = auditService;
    }

    @Override
    public List<ProcessWaitView> waits(String processInstanceId) {
        String id = processInstanceId.trim();
        ProcessInstance instance = requireRunning(tenantProvider.current().tenantId(), id);
        BpmnModel model = repositoryService.getBpmnModel(instance.getProcessDefinitionId());
        return manualWaits(id, model, null).stream()
                .map(activity -> new ProcessWaitView(ProcessWaitView.MANUAL, activity.getActivityId(),
                        activity.getActivityName(), activity.getExecutionId(), toOffset(activity.getStartTime())))
                .toList();
    }

    @Override
    public void trigger(TriggerReceiveTaskRequest request) {
        String id = request.processInstanceId().trim();
        String activityId = request.activityId().trim();
        transactionExecutor.execute(id, () -> {
            ProcessInstance instance = requireRunning(tenantProvider.current().tenantId(), id);
            BpmnModel model = repositoryService.getBpmnModel(instance.getProcessDefinitionId());
            if (!WaitStateKinds.isManualWait(model.getFlowElement(activityId))) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Bước " + activityId + " không phải bước chờ thủ công");
            }
            List<ActivityInstance> waiting = manualWaits(id, model, activityId);
            if (waiting.isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT, "Công việc không đang chờ ở bước " + activityId);
            }
            String executionId = pickExecution(waiting, request.executionId(), activityId);
            Map<String, Object> variables = ProcessVariablePolicy.clientVariables(request.variables());
            runtimeService.trigger(executionId, variables);
            String names = variables.isEmpty()
                    ? "" : " | variables=" + String.join(",", new TreeSet<>(variables.keySet()));
            auditService.record("RECEIVE_TASK_TRIGGERED", id, instance.getProcessDefinitionKey(), null,
                    activityId, "WAITING", "TRIGGERED", request.reason().trim() + names);
            return null;
        });
    }

    private List<ActivityInstance> manualWaits(String processInstanceId, BpmnModel model, String activityId) {
        var query = runtimeService.createActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityType("receiveTask")
                .unfinished();
        if (activityId != null) {
            query.activityId(activityId);
        }
        return query.list().stream()
                .filter(activity -> WaitStateKinds.isManualWait(model.getFlowElement(activity.getActivityId())))
                .sorted(Comparator.comparing(ActivityInstance::getStartTime))
                .toList();
    }

    private static String pickExecution(List<ActivityInstance> waiting, String requested, String activityId) {
        if (requested != null && !requested.isBlank()) {
            String wanted = requested.trim();
            return waiting.stream().map(ActivityInstance::getExecutionId)
                    .filter(wanted::equals).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "executionId không thuộc bước " + activityId + " đang chờ"));
        }
        if (waiting.size() > 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Bước " + activityId + " có " + waiting.size() + " nhánh đang chờ, hãy chọn executionId");
        }
        return waiting.getFirst().getExecutionId();
    }

    private ProcessInstance requireRunning(String tenantId, String processInstanceId) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId)
                .processInstanceId(processInstanceId)
                .singleResult();
        if (instance != null) {
            return instance;
        }
        if (historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(tenantId).processInstanceId(processInstanceId).count() > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process instance is already finished");
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist");
    }

    private static OffsetDateTime toOffset(Date date) {
        return date == null ? null : date.toInstant().atOffset(ZoneOffset.UTC);
    }
}
