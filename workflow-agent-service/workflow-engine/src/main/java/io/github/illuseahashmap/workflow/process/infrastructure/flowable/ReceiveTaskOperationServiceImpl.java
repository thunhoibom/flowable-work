package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.process.application.ReceiveTaskOperationService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessWaitView;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.CorrelationProbe;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.IntermediateCatchEvent;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.bpmn.model.TimerEventDefinition;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ActivityInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventsubscription.api.EventSubscription;
import org.flowable.job.api.Job;
import org.springframework.stereotype.Service;

/**
 * Lists the steps an instance waits at, manual and Event Registry ones, and continues manual waits.
 * Only a receive task without an Agent binding and without an event type can be continued here (R-F1):
 * the others are resumed by their own machinery.
 */
@Service
public class ReceiveTaskOperationServiceImpl implements ReceiveTaskOperationService {

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final ManagementService managementService;
    private final ProcessInstanceTransactionExecutor transactionExecutor;
    private final TenantProvider tenantProvider;
    private final WorkflowOperationAuditService auditService;

    public ReceiveTaskOperationServiceImpl(RuntimeService runtimeService, RepositoryService repositoryService,
                                           HistoryService historyService, ManagementService managementService,
                                           ProcessInstanceTransactionExecutor transactionExecutor,
                                           TenantProvider tenantProvider, WorkflowOperationAuditService auditService) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.managementService = managementService;
        this.transactionExecutor = transactionExecutor;
        this.tenantProvider = tenantProvider;
        this.auditService = auditService;
    }

    @Override
    public List<ProcessWaitView> waits(String processInstanceId) {
        String id = processInstanceId.trim();
        String tenantId = tenantProvider.current().tenantId();
        ProcessInstance instance = requireRunning(tenantId, id);
        BpmnModel model = repositoryService.getBpmnModel(instance.getProcessDefinitionId());
        List<Job> timers = managementService.createTimerJobQuery().processInstanceId(id).list();
        List<ProcessWaitView> waits = new ArrayList<>();
        for (ActivityInstance activity : manualWaits(id, model, null)) {
            waits.add(ProcessWaitView.manual(activity.getActivityId(), activity.getActivityName(),
                    activity.getExecutionId(), toOffset(activity.getStartTime()),
                    dueDate(model, timers, activity.getActivityId())));
        }
        for (EventSubscription subscription : runtimeService.createEventSubscriptionQuery()
                .processInstanceId(id).tenantId(tenantId).list()) {
            FlowElement element = model.getFlowElement(subscription.getActivityId());
            String eventKey = eventKey(element);
            if (eventKey == null || !eventKey.equals(subscription.getEventType())) {
                continue;
            }
            CorrelationProbe.Result probe = managementService.executeCommand(new CorrelationProbe(
                    subscription.getExecutionId(), subscription.getActivityId(), subscription.getConfiguration()));
            waits.add(new ProcessWaitView(ProcessWaitView.EVENT, element.getId(), element.getName(),
                    subscription.getExecutionId(), toOffset(subscription.getCreated()), elementType(element),
                    eventKey, probe.correlations(), probe.changed(),
                    element instanceof ReceiveTask ? dueDate(model, timers, element.getId()) : null));
        }
        waits.sort(Comparator.comparing(ProcessWaitView::since, Comparator.nullsLast(Comparator.naturalOrder())));
        return waits;
    }

    /** The event key of a receive task, intermediate catch or boundary event waiting for an event. */
    private static String eventKey(FlowElement element) {
        if (!(element instanceof ReceiveTask || element instanceof IntermediateCatchEvent
                || element instanceof BoundaryEvent)) {
            return null;
        }
        List<ExtensionElement> types = element.getExtensionElements().get(BpmnXMLConstants.ELEMENT_EVENT_TYPE);
        if (types == null || types.isEmpty() || types.getFirst().getElementText() == null) {
            return null;
        }
        return types.getFirst().getElementText().trim();
    }

    private static String elementType(FlowElement element) {
        if (element instanceof BoundaryEvent) {
            return "boundaryEvent";
        }
        return element instanceof IntermediateCatchEvent ? "intermediateCatchEvent" : "receiveTask";
    }

    /** Earliest due date of a timer boundary attached to the activity. */
    private static OffsetDateTime dueDate(BpmnModel model, List<Job> timers, String activityId) {
        return timers.stream()
                .filter(job -> model.getFlowElement(job.getElementId()) instanceof BoundaryEvent boundary
                        && activityId.equals(boundary.getAttachedToRefId())
                        && boundary.getEventDefinitions().stream().anyMatch(TimerEventDefinition.class::isInstance))
                .map(Job::getDuedate)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(ReceiveTaskOperationServiceImpl::toOffset)
                .orElse(null);
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
