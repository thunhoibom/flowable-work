package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessReleaseView;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.BpmnXmlHash;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProcessPublishServiceImpl implements ProcessPublishService {

    private final RepositoryService repositoryService;
    private final ProcessDraftRepository drafts;
    private final ProcessReleaseRepository releases;
    private final ProcessDefinitionLookup lookup;
    private final BpmnDraftInspector inspector;
    private final ProcessDeployer deployer;
    private final AssignmentRuleService assignmentRuleService;
    private final WorkflowOperationAuditService auditService;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;

    public ProcessPublishServiceImpl(RepositoryService repositoryService,
                                     ProcessDraftRepository drafts, ProcessReleaseRepository releases,
                                     ProcessDefinitionLookup lookup, BpmnDraftInspector inspector,
                                     ProcessDeployer deployer, AssignmentRuleService assignmentRuleService,
                                     WorkflowOperationAuditService auditService,
                                     TenantProvider tenantProvider, CurrentPrincipalProvider principalProvider) {
        this.repositoryService = repositoryService;
        this.drafts = drafts;
        this.releases = releases;
        this.lookup = lookup;
        this.inspector = inspector;
        this.deployer = deployer;
        this.assignmentRuleService = assignmentRuleService;
        this.auditService = auditService;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PublishProcessResult publish(String key, PublishProcessRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDraft draft = drafts.findForUpdate(tenant.tenantId(), key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process draft does not exist"));
        if (draft.revision() != request.revision()) {
            throw new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT,
                    "Bản nháp đã được người khác sửa. Hãy tải lại.");
        }
        ProcessDefinition previous = lookup.latest(tenant.tenantId(), key).orElse(null);
        if (previous != null && !previous.isSuspended()
                && BpmnXmlHash.of(lookup.xml(previous)).equals(draft.xmlSha256())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Bản nháp giống bản đang chạy, không có gì để phát hành");
        }
        ProcessDefinition definition = deployer.deployChecked(tenant, key, draft.name(), draft.bpmnXml());
        AssignmentRuleInheritResult rules = previous == null
                ? null : assignmentRuleService.copyRules(previous.getId(), definition.getId());
        deployer.record(tenant, definition, ReleaseAction.PUBLISH, null, draft.xmlSha256());
        auditService.record("PROCESS_DEFINITION_PUBLISHED", null, key, null,
                "version=" + definition.getVersion(), null, "PUBLISHED", null);
        return result(definition, rules, previous);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PublishProcessResult publishXml(String key, String name, String bpmnXml) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        inspector.check(key, bpmnXml);
        ProcessDraft current = drafts.findForUpdate(tenant.tenantId(), key).orElse(null);
        ProcessDraft next = current == null
                ? ProcessDraft.of(tenant.tenantId(), key, name, bpmnXml, 1, actor(), now())
                : current.next(name, bpmnXml, actor(), now());
        boolean written = current == null ? drafts.insert(next) : drafts.update(next, current.revision());
        if (!written) {
            throw new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT, "Process draft changed concurrently");
        }
        // CI may redeploy the same XML. Return the running version instead of failing.
        ProcessDefinition latest = lookup.latest(tenant.tenantId(), key).orElse(null);
        if (latest != null && !latest.isSuspended()
                && BpmnXmlHash.of(lookup.xml(latest)).equals(next.xmlSha256())) {
            return new PublishProcessResult(latest.getDeploymentId(), latest.getId(), latest.getKey(),
                    latest.getName(), latest.getVersion(), null, List.of());
        }
        return publish(key, new PublishProcessRequest(next.revision()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PublishProcessResult rollback(String key, RollbackProcessRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDefinition latest = requireLatest(tenant.tenantId(), key);
        ProcessDefinition source = lookup.version(tenant.tenantId(), key, request.version())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process version does not exist"));
        if (source.getId().equals(latest.getId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "This is already the latest version");
        }
        String xml = lookup.xml(source);
        ProcessDefinition definition = deployer.deployChecked(tenant, key, source.getName(), xml);
        AssignmentRuleInheritResult rules = assignmentRuleService.copyRules(source.getId(), definition.getId());
        deployer.record(tenant, definition, ReleaseAction.ROLLBACK, source.getVersion(), BpmnXmlHash.of(xml));
        auditService.record("PROCESS_DEFINITION_ROLLED_BACK", null, key, null,
                "version=" + definition.getVersion() + ", source=" + source.getVersion(), null, "PUBLISHED", null);
        return result(definition, rules, latest);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessReleaseView suspend(String key) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDefinition latest = requireLatest(tenant.tenantId(), key);
        if (latest.isSuspended()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process is already suspended");
        }
        repositoryService.suspendProcessDefinitionById(latest.getId(), false, null);
        auditService.record("PROCESS_DEFINITION_SUSPENDED", null, key, null,
                "version=" + latest.getVersion(), "PUBLISHED", "SUSPENDED", null);
        return view(deployer.record(tenant, latest, ReleaseAction.SUSPEND, null,
                BpmnXmlHash.of(lookup.xml(latest))));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessReleaseView resume(String key) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDefinition latest = requireLatest(tenant.tenantId(), key);
        if (!latest.isSuspended()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process is not suspended");
        }
        repositoryService.activateProcessDefinitionById(latest.getId(), false, null);
        auditService.record("PROCESS_DEFINITION_RESUMED", null, key, null,
                "version=" + latest.getVersion(), "SUSPENDED", "PUBLISHED", null);
        return view(deployer.record(tenant, latest, ReleaseAction.RESUME, null,
                BpmnXmlHash.of(lookup.xml(latest))));
    }

    @Override
    public List<ProcessReleaseView> releases(String key) {
        return releases.list(tenantProvider.current().tenantId(), key).stream().map(this::view).toList();
    }

    private PublishProcessResult result(ProcessDefinition definition, AssignmentRuleInheritResult rules,
                                        ProcessDefinition previous) {
        Set<String> added = userTaskIds(definition.getId());
        if (previous != null) {
            added.removeAll(userTaskIds(previous.getId()));
        }
        return new PublishProcessResult(definition.getDeploymentId(), definition.getId(), definition.getKey(),
                definition.getName(), definition.getVersion(), rules, List.copyOf(added));
    }

    private Set<String> userTaskIds(String definitionId) {
        Set<String> ids = new LinkedHashSet<>();
        repositoryService.getBpmnModel(definitionId).getMainProcess()
                .findFlowElementsOfType(UserTask.class, true)
                .forEach(task -> ids.add(task.getId()));
        return ids;
    }

    private ProcessDefinition requireLatest(String tenantId, String key) {
        return lookup.latest(tenantId, key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process has no published version"));
    }

    private ProcessReleaseView view(ProcessRelease release) {
        return new ProcessReleaseView(release.version(), release.action().name(), release.sourceVersion(),
                release.performedBy(), release.performedAt());
    }

    private String actor() {
        CurrentPrincipal principal = principalProvider.current();
        return StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
