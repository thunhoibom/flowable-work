package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionDiagramView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDiagramDataView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceDetailView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessReferenceView;
import io.github.illuseahashmap.workflow.process.application.dto.TerminateProcessRequest;
import io.github.illuseahashmap.workflow.process.application.port.CallReferenceRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowAdministrationServiceImpl implements WorkflowAdministrationService {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final JdbcTemplate jdbcTemplate;
    private final ProcessInstanceTransactionExecutor transactionExecutor;
    private final WorkflowDefinitionReadService definitionReadService;
    private final WorkflowInstanceReadService instanceReadService;
    private final TenantProvider tenantProvider;
    private final AssignmentRuleService assignmentRuleService;
    private final WorkflowOperationAuditService auditService;
    private final ProcessDraftRepository drafts;
    private final ProcessReleaseRepository releases;
    private final CallReferenceRepository callReferences;

    public WorkflowAdministrationServiceImpl(RepositoryService repositoryService,
                                             RuntimeService runtimeService,
                                             HistoryService historyService,
                                             JdbcTemplate jdbcTemplate,
                                             ProcessInstanceTransactionExecutor transactionExecutor,
                                             WorkflowDefinitionReadService definitionReadService,
                                             WorkflowInstanceReadService instanceReadService,
                                             TenantProvider tenantProvider,
                                             AssignmentRuleService assignmentRuleService,
                                             WorkflowOperationAuditService auditService,
                                             ProcessDraftRepository drafts,
                                             ProcessReleaseRepository releases,
                                             CallReferenceRepository callReferences) {
        this.drafts = drafts;
        this.releases = releases;
        this.callReferences = callReferences;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionExecutor = transactionExecutor;
        this.definitionReadService = definitionReadService;
        this.instanceReadService = instanceReadService;
        this.tenantProvider = tenantProvider;
        this.assignmentRuleService = assignmentRuleService;
        this.auditService = auditService;
    }

    @Override
    public byte[] generateProcessDiagram(String processInstanceId) {
        return instanceReadService.generateDiagram(processInstanceId);
    }

    @Override
    public ProcessDiagramDataView getProcessDiagramData(String processInstanceId) {
        return instanceReadService.diagramData(processInstanceId);
    }

    @Override
    public PageResult<ProcessDefinitionSummaryView> pageProcessDefinitions(
            Integer pageNum, Integer pageSize, String key, String name, String publishStatus) {
        return definitionReadService.page(pageNum, pageSize, key, name, publishStatus);
    }

    @Override
    public PageResult<ProcessInstanceSummaryView> pageProcessInstances(
            Integer pageNum, Integer pageSize, String definitionKey, String definitionName,
            String processInstanceId, String businessKey, String status, boolean rootOnly) {
        return instanceReadService.page(
                pageNum, pageSize, definitionKey, definitionName, processInstanceId, businessKey, status, rootOnly);
    }

    @Override
    public ProcessInstanceDetailView getProcessInstanceDetail(String processInstanceId) {
        return instanceReadService.detail(processInstanceId);
    }

    @Override
    public void terminateProcessInstance(TerminateProcessRequest request) {
        String processInstanceId = request.processInstanceId().trim();
        transactionExecutor.execute(processInstanceId, () -> {
            String tenantId = tenantProvider.current().tenantId();
            ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                    .processInstanceTenantId(tenantId)
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (instance == null) {
                if (historyService.createHistoricProcessInstanceQuery()
                        .processInstanceTenantId(tenantId)
                        .processInstanceId(processInstanceId)
                        .count() > 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "Process instance is already finished");
                }
                throw new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist");
            }
            if (instance.getSuperExecutionId() != null) {
                // Ending only the child would leave the parent waiting at its call activity forever.
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Đây là quy trình con. Hãy kết thúc instance gốc " + instance.getRootProcessInstanceId());
            }
            List<ProcessInstance> descendants = descendants(tenantId, processInstanceId);
            runtimeService.deleteProcessInstance(processInstanceId, request.reason().trim());
            auditService.record(
                    "PROCESS_TERMINATED", processInstanceId, instance.getProcessDefinitionKey(),
                    null, instance.getBusinessKey(), "RUNNING", "TERMINATED", request.reason());
            for (ProcessInstance descendant : descendants) {
                auditService.record(
                        "PROCESS_TERMINATED", descendant.getId(), descendant.getProcessDefinitionKey(),
                        null, descendant.getBusinessKey(), "RUNNING", "TERMINATED",
                        "cascade from " + processInstanceId);
            }
            return null;
        });
    }

    /** Every running instance below this one, collected before Flowable deletes them in cascade. */
    private List<ProcessInstance> descendants(String tenantId, String processInstanceId) {
        List<ProcessInstance> result = new java.util.ArrayList<>();
        java.util.Deque<String> pending = new java.util.ArrayDeque<>(List.of(processInstanceId));
        while (!pending.isEmpty() && result.size() < 1000) {
            for (ProcessInstance child : runtimeService.createProcessInstanceQuery()
                    .processInstanceTenantId(tenantId)
                    .superProcessInstanceId(pending.removeFirst())
                    .list()) {
                result.add(child);
                pending.addLast(child.getId());
            }
        }
        return result;
    }

    @Override
    public ProcessDefinitionDiagramView getProcessDefinitionDiagram(
            String processDefinitionKey, Integer version, String processDefinitionId) {
        return definitionReadService.diagram(processDefinitionKey, version, processDefinitionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProcessDefinitions(String processDefinitionKey) {
        String tenantId = tenantProvider.current().tenantId();
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .processDefinitionKey(processDefinitionKey)
                .list();
        if (definitions.isEmpty()) {
            if (drafts.find(tenantId, processDefinitionKey).isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
            }
            drafts.delete(tenantId, processDefinitionKey);
            auditService.record(
                    "PROCESS_DRAFT_DELETED", null, processDefinitionKey,
                    null, processDefinitionKey, "DRAFT", "DELETED", null);
            return;
        }
        long running = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId).processDefinitionKey(processDefinitionKey).count();
        if (running > 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Còn " + running + " instance đang chạy. Hãy kết thúc chúng hoặc ngừng phát hành.");
        }
        List<String> callers = publishedCallers(tenantId, processDefinitionKey);
        if (!callers.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Quy trình đang được gọi bởi: " + String.join(", ", callers));
        }
        assignmentRuleService.deleteByProcessDefinitionKey(processDefinitionKey);
        definitions.stream().map(ProcessDefinition::getDeploymentId).distinct()
                .forEach(deploymentId -> repositoryService.deleteDeployment(deploymentId, true));
        drafts.delete(tenantId, processDefinitionKey);
        releases.deleteByKey(tenantId, processDefinitionKey);
        callReferences.deleteForKey(tenantId, processDefinitionKey);
        // Legacy table, kept until it is dropped in a later migration.
        jdbcTemplate.update("""
                DELETE FROM workflow_active_version WHERE tenant_id = ? AND process_definition_key = ?
                """, tenantId, processDefinitionKey);
        auditService.record(
                "PROCESS_DEFINITIONS_DELETED", null, processDefinitionKey,
                null, processDefinitionKey, "DEPLOYED", "DELETED", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProcessDefinitionVersion(String processDefinitionKey, Integer version) {
        if (version == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Process definition version is required");
        }
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .processDefinitionKey(processDefinitionKey)
                .processDefinitionVersion(version)
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition version does not exist");
        }
        ProcessDefinition latest = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .processDefinitionKey(processDefinitionKey)
                .latestVersion()
                .singleResult();
        if (latest != null && latest.getId().equals(definition.getId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Không xoá được bản mới nhất. Hãy ngừng phát hành nếu không muốn dùng nữa.");
        }
        long running = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId).processDefinitionId(definition.getId()).count();
        if (running > 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Còn " + running + " instance đang chạy trên version này");
        }
        assignmentRuleService.deleteByProcessDefinition(definition.getId());
        callReferences.deleteForDefinition(tenantId, definition.getId());
        repositoryService.deleteDeployment(definition.getDeploymentId(), true);
        jdbcTemplate.update("""
                DELETE FROM workflow_active_version
                WHERE tenant_id = ? AND process_definition_key = ? AND process_definition_id = ?
                """, tenantId, processDefinitionKey, definition.getId());
        auditService.record(
                "PROCESS_DEFINITION_VERSION_DELETED", null, processDefinitionKey,
                null, "version=" + version, "DEPLOYED", "DELETED", null);
    }

    @Override
    public List<ProcessReferenceView> callers(String processDefinitionKey) {
        String tenantId = tenantProvider.current().tenantId();
        java.util.Map<String, ProcessDefinition> published = publishedByKey(tenantId);
        return callReferences.findCallers(tenantId, processDefinitionKey).stream()
                .filter(reference -> !reference.parentDefinitionKey().equals(processDefinitionKey))
                .filter(reference -> {
                    ProcessDefinition parent = published.get(reference.parentDefinitionKey());
                    return parent != null && parent.getId().equals(reference.parentDefinitionId());
                })
                .map(reference -> new ProcessReferenceView(reference.parentDefinitionKey(),
                        published.get(reference.parentDefinitionKey()).getName(),
                        reference.parentVersion(), reference.activityId(), true))
                .toList();
    }

    @Override
    public List<ProcessReferenceView> callees(String processDefinitionKey, Integer version) {
        String tenantId = tenantProvider.current().tenantId();
        var query = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).processDefinitionKey(processDefinitionKey);
        ProcessDefinition definition = version == null
                ? query.latestVersion().singleResult()
                : query.processDefinitionVersion(version).singleResult();
        if (definition == null) {
            return List.of();
        }
        java.util.Map<String, ProcessDefinition> published = publishedByKey(tenantId);
        return callReferences.findByDefinition(tenantId, definition.getId()).stream()
                .map(reference -> {
                    ProcessDefinition callee = published.get(reference.calledKey());
                    return new ProcessReferenceView(reference.calledKey(),
                            callee == null ? null : callee.getName(),
                            callee == null ? null : callee.getVersion(),
                            reference.activityId(), callee != null);
                })
                .toList();
    }

    /** Other keys whose published version calls this key, at most ten, as "key vN". */
    private List<String> publishedCallers(String tenantId, String processDefinitionKey) {
        return callers(processDefinitionKey).stream()
                .map(caller -> caller.processDefinitionKey() + " v" + caller.version())
                .distinct()
                .limit(10)
                .toList();
    }

    private java.util.Map<String, ProcessDefinition> publishedByKey(String tenantId) {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).latestVersion().list().stream()
                .filter(definition -> !definition.isSuspended())
                .collect(java.util.stream.Collectors.toMap(ProcessDefinition::getKey, definition -> definition));
    }
}
