package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.RestChannelService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.RestChannelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveRestChannelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryDraftRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryReleaseRepository;
import io.github.illuseahashmap.workflow.eventregistry.domain.KeyDetectionMode;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryDraft;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryRelease;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelRules;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RestChannelServiceImpl implements RestChannelService {

    private final EventRegistryDraftRepository drafts;
    private final EventRegistryReleaseRepository releases;
    private final EventRegistryGateway gateway;
    private final WorkflowOperationAuditService auditService;
    private final TenantProvider tenants;
    private final CurrentPrincipalProvider principals;
    private final EventRegistryProperties properties;

    public RestChannelServiceImpl(EventRegistryDraftRepository drafts, EventRegistryReleaseRepository releases,
                                  EventRegistryGateway gateway, WorkflowOperationAuditService auditService,
                                  TenantProvider tenants, CurrentPrincipalProvider principals,
                                  EventRegistryProperties properties) {
        this.drafts = drafts;
        this.releases = releases;
        this.gateway = gateway;
        this.auditService = auditService;
        this.tenants = tenants;
        this.principals = principals;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RestChannelView> list() {
        String tenantId = tenants.current().tenantId();
        return drafts.list(tenantId, RegistryModelType.CHANNEL).stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RestChannelView get(String key) {
        return view(require(tenants.current().tenantId(), key, false));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RestChannelView saveDraft(String key, SaveRestChannelRequest request) {
        RegistrySupport.requireEnabled(properties);
        String tenantId = tenants.current().tenantId();
        RestChannelDefinition definition = new RestChannelDefinition(key, request.name().trim(),
                RegistrySupport.trimToNull(request.description()), detection(request.detection()),
                request.detectionValue().trim(), trimmed(request.allowedEventKeys()), trimmed(request.userIds()),
                trimmed(request.groupIds()));
        RegistrySupport.requireValid(RestChannelRules.validate(definition));
        String json = RegistryDraftJson.channel(definition);
        if (request.revision() == 0) {
            RegistryDraft created = new RegistryDraft(tenantId, RegistryModelType.CHANNEL, key, definition.name(),
                    definition.description(), json, 1, RegistrySupport.actor(principals), RegistrySupport.now());
            if (!drafts.insert(created)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Kênh đã tồn tại");
            }
            return view(created);
        }
        RegistryDraft current = require(tenantId, key, true);
        RegistryDraft next = current.next(definition.name(), definition.description(), json,
                RegistrySupport.actor(principals), RegistrySupport.now());
        if (current.revision() != request.revision() || !drafts.update(next, request.revision())) {
            throw RegistrySupport.revisionConflict();
        }
        return view(next);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RestChannelView publish(String key, long revision) {
        RegistrySupport.requireEnabled(properties);
        String tenantId = tenants.current().tenantId();
        RegistryDraft draft = require(tenantId, key, true);
        if (draft.revision() != revision) {
            throw RegistrySupport.revisionConflict();
        }
        RestChannelDefinition definition = definition(draft);
        RegistrySupport.requireValid(RestChannelRules.validate(definition));
        for (String eventKey : definition.allowedEventKeys()) {
            if (gateway.publishedEventFields(tenantId, eventKey).isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT, "Sự kiện " + eventKey + " chưa được phát hành");
            }
        }
        String json = FlowableEventRegistryJson.channel(definition, tenantId);
        String sha = FlowableEventRegistryJson.sha256(json);
        boolean unchanged = gateway.channelDeployed(tenantId, key)
                && releases.latest(tenantId, RegistryModelType.CHANNEL, key).filter(RegistryRelease::isPublish)
                        .map(release -> sha.equals(release.deployedJsonSha256())).orElse(false);
        if (unchanged) {
            return view(draft);
        }
        EventRegistryGateway.Deployed deployed = gateway.deployChannel(tenantId, key, json);
        releases.record(new RegistryRelease(tenantId, RegistryModelType.CHANNEL, key, deployed.version(),
                deployed.definitionId(), deployed.deploymentId(), sha, RegistryRelease.PUBLISH,
                RegistrySupport.actor(principals), RegistrySupport.now()));
        auditService.record("EVENT_CHANNEL_PUBLISHED", null, null, null, key, null, "PUBLISHED",
                "version=" + deployed.version());
        return view(draft);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String key) {
        RegistrySupport.requireEnabled(properties);
        String tenantId = tenants.current().tenantId();
        require(tenantId, key, true);
        boolean wasPublished = releases.latest(tenantId, RegistryModelType.CHANNEL, key)
                .map(RegistryRelease::isPublish).orElse(false);
        gateway.deleteDeployments(tenantId, RegistryModelType.CHANNEL, key);
        drafts.delete(tenantId, RegistryModelType.CHANNEL, key);
        if (wasPublished) {
            releases.record(new RegistryRelease(tenantId, RegistryModelType.CHANNEL, key, null, null, null, null,
                    RegistryRelease.DELETE, RegistrySupport.actor(principals), RegistrySupport.now()));
        }
        auditService.record("EVENT_CHANNEL_DELETED", null, null, null, key, wasPublished ? "PUBLISHED" : "DRAFT",
                "DELETED", null);
    }

    private RegistryDraft require(String tenantId, String key, boolean lock) {
        Optional<RegistryDraft> draft = lock
                ? drafts.findForUpdate(tenantId, RegistryModelType.CHANNEL, key)
                : drafts.find(tenantId, RegistryModelType.CHANNEL, key);
        return draft.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Không có kênh " + key));
    }

    private static RestChannelDefinition definition(RegistryDraft draft) {
        return RegistryDraftJson.channel(draft.key(), draft.name(), draft.description(), draft.modelJson());
    }

    /** Null for an unknown mode; the rules then report it. */
    private static KeyDetectionMode detection(String value) {
        String wanted = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(KeyDetectionMode.values()).filter(mode -> mode.name().equals(wanted))
                .findFirst().orElse(null);
    }

    private static List<String> trimmed(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null).map(String::trim).toList();
    }

    private RestChannelView view(RegistryDraft draft) {
        RestChannelDefinition definition = definition(draft);
        Optional<RegistryRelease> release = releases.latest(draft.tenantId(), RegistryModelType.CHANNEL, draft.key())
                .filter(RegistryRelease::isPublish);
        boolean changed = release.isEmpty() || !gateway.channelDeployed(draft.tenantId(), draft.key())
                || !FlowableEventRegistryJson.sha256(FlowableEventRegistryJson.channel(definition, draft.tenantId()))
                        .equals(release.get().deployedJsonSha256());
        return new RestChannelView(draft.key(), draft.name(), draft.description(), definition.detection().name(),
                definition.detectionValue(), definition.allowedEventKeys(), definition.userIds(),
                definition.groupIds(), draft.revision(), draft.updatedBy(), draft.updatedAt(),
                release.map(RegistryRelease::flowableVersion).orElse(null),
                release.map(RegistryRelease::performedAt).orElse(null),
                release.map(RegistryRelease::performedBy).orElse(null),
                changed, RestChannelView.webhookPath(draft.key()));
    }
}
