package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.EventModelService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventModelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveEventModelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryDraftRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryReleaseRepository;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelRules;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryDraft;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryRelease;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventModelServiceImpl implements EventModelService {

    private final EventRegistryDraftRepository drafts;
    private final EventRegistryReleaseRepository releases;
    private final EventReferenceRepository references;
    private final EventRegistryGateway gateway;
    private final WorkflowOperationAuditService auditService;
    private final TenantProvider tenants;
    private final CurrentPrincipalProvider principals;
    private final EventRegistryProperties properties;

    public EventModelServiceImpl(EventRegistryDraftRepository drafts, EventRegistryReleaseRepository releases,
                                 EventReferenceRepository references, EventRegistryGateway gateway,
                                 WorkflowOperationAuditService auditService, TenantProvider tenants,
                                 CurrentPrincipalProvider principals, EventRegistryProperties properties) {
        this.drafts = drafts;
        this.releases = releases;
        this.references = references;
        this.gateway = gateway;
        this.auditService = auditService;
        this.tenants = tenants;
        this.principals = principals;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventModelView> list() {
        String tenantId = tenants.current().tenantId();
        return drafts.list(tenantId, RegistryModelType.EVENT).stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EventModelView get(String key) {
        return view(require(tenants.current().tenantId(), key, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EventModelView saveDraft(String key, SaveEventModelRequest request) {
        RegistrySupport.requireEnabled(properties);
        String tenantId = tenants.current().tenantId();
        List<EventField> fields = request.fields().stream().map(RegistryDraftJson::field).toList();
        EventModelDefinition definition = new EventModelDefinition(key, request.name().trim(),
                RegistrySupport.trimToNull(request.description()), fields);
        RegistrySupport.requireValid(EventModelRules.validate(definition));
        String json = RegistryDraftJson.eventFields(definition.fields());
        if (request.revision() == 0) {
            RegistryDraft created = new RegistryDraft(tenantId, RegistryModelType.EVENT, key, definition.name(),
                    definition.description(), json, 1, RegistrySupport.actor(principals), RegistrySupport.now());
            if (!drafts.insert(created)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Sự kiện đã tồn tại");
            }
            return view(created);
        }
        RegistryDraft current = drafts.findForUpdate(tenantId, RegistryModelType.EVENT, key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Không có sự kiện " + key));
        RegistryDraft next = current.next(definition.name(), definition.description(), json,
                RegistrySupport.actor(principals), RegistrySupport.now());
        if (current.revision() != request.revision() || !drafts.update(next, request.revision())) {
            throw RegistrySupport.revisionConflict();
        }
        return view(next);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EventModelView publish(String key, long revision) {
        RegistrySupport.requireEnabled(properties);
        String tenantId = tenants.current().tenantId();
        RegistryDraft draft = require(tenantId, key, true);
        if (draft.revision() != revision) {
            throw RegistrySupport.revisionConflict();
        }
        EventModelDefinition definition = definition(draft);
        RegistrySupport.requireValid(EventModelRules.validate(definition));
        Optional<List<EventField>> published = gateway.publishedEventFields(tenantId, key);
        if (published.isPresent()) {
            List<String> problems = EventModelRules.incompatibilities(published.get(), definition.fields());
            if (!problems.isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT, "Thay đổi không tương thích: "
                        + String.join("; ", problems) + ". Hãy tạo mã sự kiện mới.");
            }
        }
        String json = FlowableEventRegistryJson.event(definition);
        String sha = FlowableEventRegistryJson.sha256(json);
        Optional<RegistryRelease> latest = releases.latest(tenantId, RegistryModelType.EVENT, key);
        if (published.isPresent() && latest.filter(RegistryRelease::isPublish)
                .map(release -> sha.equals(release.deployedJsonSha256())).orElse(false)) {
            return view(draft);
        }
        EventRegistryGateway.Deployed deployed = gateway.deployEvent(tenantId, key, json);
        releases.record(new RegistryRelease(tenantId, RegistryModelType.EVENT, key, deployed.version(),
                deployed.definitionId(), deployed.deploymentId(), sha, RegistryRelease.PUBLISH,
                RegistrySupport.actor(principals), RegistrySupport.now()));
        auditService.record("EVENT_MODEL_PUBLISHED", null, null, null, key, null, "PUBLISHED",
                "version=" + deployed.version());
        return view(draft);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String key) {
        RegistrySupport.requireEnabled(properties);
        String tenantId = tenants.current().tenantId();
        require(tenantId, key, true);
        long waiting = gateway.waitingSubscriptions(tenantId, key);
        if (waiting > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Còn " + waiting + " công việc đang chờ sự kiện này");
        }
        if (references.latestProcessDefinitionsUsing(tenantId, key) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Quy trình đang dùng sự kiện này");
        }
        drafts.list(tenantId, RegistryModelType.CHANNEL).stream()
                .filter(channel -> RegistryDraftJson.channel(channel.key(), channel.name(), channel.description(),
                        channel.modelJson()).allowedEventKeys().contains(key))
                .findFirst()
                .ifPresent(channel -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "Kênh " + channel.key() + " đang dùng sự kiện này");
                });
        boolean wasPublished = releases.latest(tenantId, RegistryModelType.EVENT, key)
                .map(RegistryRelease::isPublish).orElse(false);
        gateway.deleteDeployments(tenantId, RegistryModelType.EVENT, key);
        drafts.delete(tenantId, RegistryModelType.EVENT, key);
        if (wasPublished) {
            releases.record(new RegistryRelease(tenantId, RegistryModelType.EVENT, key, null, null, null, null,
                    RegistryRelease.DELETE, RegistrySupport.actor(principals), RegistrySupport.now()));
        }
        auditService.record("EVENT_MODEL_DELETED", null, null, null, key, wasPublished ? "PUBLISHED" : "DRAFT",
                "DELETED", null);
    }

    private RegistryDraft require(String tenantId, String key, boolean lock) {
        Optional<RegistryDraft> draft = lock
                ? drafts.findForUpdate(tenantId, RegistryModelType.EVENT, key)
                : drafts.find(tenantId, RegistryModelType.EVENT, key);
        return draft.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Không có sự kiện " + key));
    }

    private static EventModelDefinition definition(RegistryDraft draft) {
        return new EventModelDefinition(draft.key(), draft.name(), draft.description(),
                RegistryDraftJson.eventFields(draft.modelJson()));
    }

    private EventModelView view(RegistryDraft draft) {
        EventModelDefinition definition = definition(draft);
        Optional<RegistryRelease> release = releases.latest(draft.tenantId(), RegistryModelType.EVENT, draft.key())
                .filter(RegistryRelease::isPublish);
        List<EventFieldView> publishedFields = release.isEmpty() ? null
                : gateway.publishedEventFields(draft.tenantId(), draft.key())
                        .map(fields -> fields.stream().map(RegistryDraftJson::view).toList())
                        .orElse(null);
        boolean changed = release.isEmpty() || publishedFields == null
                || !FlowableEventRegistryJson.sha256(FlowableEventRegistryJson.event(definition))
                        .equals(release.get().deployedJsonSha256());
        return new EventModelView(draft.key(), draft.name(), draft.description(),
                definition.fields().stream().map(RegistryDraftJson::view).toList(),
                draft.revision(), draft.updatedBy(), draft.updatedAt(),
                release.map(RegistryRelease::flowableVersion).orElse(null),
                release.map(RegistryRelease::performedAt).orElse(null),
                release.map(RegistryRelease::performedBy).orElse(null),
                publishedFields, changed);
    }
}
