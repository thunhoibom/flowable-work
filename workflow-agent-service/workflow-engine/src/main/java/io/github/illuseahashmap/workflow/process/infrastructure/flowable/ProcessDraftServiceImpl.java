package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.ProcessDraftService;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftRevisionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDraftView;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.domain.BpmnXmlHash;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProcessDraftServiceImpl implements ProcessDraftService {

    private final ProcessDraftRepository drafts;
    private final ProcessDefinitionLookup lookup;
    private final BpmnDraftInspector inspector;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;

    public ProcessDraftServiceImpl(ProcessDraftRepository drafts, ProcessDefinitionLookup lookup,
                                   BpmnDraftInspector inspector, TenantProvider tenantProvider,
                                   CurrentPrincipalProvider principalProvider) {
        this.drafts = drafts;
        this.lookup = lookup;
        this.inspector = inspector;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView create(CreateProcessDraftRequest request) {
        String tenantId = tenantId();
        String key = request.processDefinitionKey().trim();
        inspector.check(key, request.bpmnXml());
        if (lookup.anyVersion(tenantId, key)
                || !drafts.insert(ProcessDraft.of(tenantId, key, request.name().trim(), request.bpmnXml(),
                        1, actor(), now()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process key already exists: " + key);
        }
        return get(key);
    }

    @Override
    public ProcessDraftView get(String key) {
        String tenantId = tenantId();
        Optional<ProcessDefinition> latest = lookup.latest(tenantId, key);
        Optional<ProcessDraft> draft = drafts.find(tenantId, key);
        if (draft.isEmpty() && latest.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process draft does not exist");
        }
        if (draft.isEmpty()) {
            ProcessDefinition definition = latest.get();
            return new ProcessDraftView(key, nameOf(definition), lookup.xml(definition), 0, null, null,
                    definition.getVersion(), definition.isSuspended(), false);
        }
        ProcessDraft value = draft.get();
        String latestHash = latest.map(definition -> BpmnXmlHash.of(lookup.xml(definition))).orElse(null);
        return new ProcessDraftView(key, value.name(), value.bpmnXml(), value.revision(), value.updatedBy(),
                value.updatedAt(), latest.map(ProcessDefinition::getVersion).orElse(null),
                latest.map(ProcessDefinition::isSuspended).orElse(false),
                latestHash == null || !latestHash.equals(value.xmlSha256()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView save(String key, SaveProcessDraftRequest request) {
        inspector.check(key, request.bpmnXml());
        write(key, request.name().trim(), request.bpmnXml(), request.revision());
        return get(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView loadVersion(String key, DraftVersionRequest request) {
        ProcessDefinition definition = lookup.version(tenantId(), key, request.version())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process version does not exist"));
        write(key, nameOf(definition), lookup.xml(definition), request.revision());
        return get(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView discard(String key, DraftRevisionRequest request) {
        ProcessDefinition latest = lookup.latest(tenantId(), key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process has no published version"));
        write(key, nameOf(latest), lookup.xml(latest), request.revision());
        return get(key);
    }

    /** Revision 0 means "no draft row yet", which is only valid for keys that exist in the engine. */
    private void write(String key, String name, String xml, long expectedRevision) {
        String tenantId = tenantId();
        Optional<ProcessDraft> current = drafts.find(tenantId, key);
        boolean written;
        if (current.isPresent()) {
            written = drafts.update(current.get().next(name, xml, actor(), now()), expectedRevision);
        } else if (!lookup.anyVersion(tenantId, key)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process draft does not exist");
        } else {
            written = expectedRevision == 0
                    && drafts.insert(ProcessDraft.of(tenantId, key, name, xml, 1, actor(), now()));
        }
        if (!written) {
            throw new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT,
                    "Bản nháp đã được người khác sửa. Hãy tải lại.");
        }
    }

    private static String nameOf(ProcessDefinition definition) {
        return StringUtils.hasText(definition.getName()) ? definition.getName() : definition.getKey();
    }

    private String tenantId() {
        return tenantProvider.current().tenantId();
    }

    private String actor() {
        CurrentPrincipal principal = principalProvider.current();
        return StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
