package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.dto.ActivateProcessVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ActiveProcessVersionResult;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionView;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Read side of process definitions. The running version of a key is the latest
 * definition in the tenant that is not suspended.
 */
@Service
public class WorkflowDefinitionServiceImpl implements WorkflowDefinitionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowDefinitionServiceImpl.class);

    private final RepositoryService repositoryService;
    private final TenantProvider tenantProvider;
    private final ProcessPublishService publishService;
    private final ProcessReleaseRepository releases;
    private final ProcessDraftRepository drafts;
    private final ProcessDefinitionLookup lookup;

    public WorkflowDefinitionServiceImpl(RepositoryService repositoryService,
                                         TenantProvider tenantProvider,
                                         ProcessPublishService publishService,
                                         ProcessReleaseRepository releases,
                                         ProcessDraftRepository drafts,
                                         ProcessDefinitionLookup lookup) {
        this.repositoryService = repositoryService;
        this.tenantProvider = tenantProvider;
        this.publishService = publishService;
        this.releases = releases;
        this.drafts = drafts;
        this.lookup = lookup;
    }

    /** CI/SERVICE entry point: overwrites the draft and publishes it in one call. */
    @Override
    public DeployProcessResult deploy(DeployProcessRequest request) {
        PublishProcessResult result = publishService.publishXml(
                request.processDefinitionKey().trim(), request.processDefinitionName().trim(), request.bpmnXml());
        return new DeployProcessResult(result.deploymentId(), result.processDefinitionId(),
                result.processDefinitionKey(), result.processDefinitionName(), result.version());
    }

    @Override
    public String runningProcessDefinitionId(String processDefinitionKey) {
        ProcessDefinition latest = lookup.latest(tenantProvider.current().tenantId(), processDefinitionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist"));
        if (latest.isSuspended()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Quy trình đã ngừng phát hành: " + processDefinitionKey);
        }
        return latest.getId();
    }

    /**
     * Kept for one release. The latest version resumes it, an older version rolls back to it.
     *
     * @deprecated use publish, resume or rollback
     */
    @Override
    @Deprecated
    @Transactional(rollbackFor = Exception.class)
    public ActiveProcessVersionResult activate(ActivateProcessVersionRequest request) {
        LOGGER.warn("Deprecated /definition/activate called for {} v{}",
                request.processDefinitionKey(), request.version());
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition latest = lookup.latest(tenantId, request.processDefinitionKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Process definition version does not exist"));
        if (request.version() == latest.getVersion()) {
            if (latest.isSuspended()) {
                publishService.resume(request.processDefinitionKey());
            }
        } else if (request.version() < latest.getVersion()) {
            publishService.rollback(request.processDefinitionKey(), new RollbackProcessRequest(request.version()));
        } else {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition version does not exist");
        }
        return getActiveVersion(request.processDefinitionKey());
    }

    @Override
    public ActiveProcessVersionResult getActiveVersion(String processDefinitionKey) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition latest = lookup.latest(tenantId, processDefinitionKey)
                .filter(definition -> !definition.isSuspended())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Active process definition version does not exist"));
        ProcessRelease release = releases.latestByDefinition(tenantId).get(latest.getId());
        return new ActiveProcessVersionResult(tenantId, processDefinitionKey, latest.getId(), latest.getVersion(),
                release == null ? null : release.performedBy(),
                release == null ? deploymentTime(latest.getDeploymentId()) : release.performedAt());
    }

    @Override
    public boolean exists(String processDefinitionKey) {
        String tenantId = tenantProvider.current().tenantId();
        return lookup.anyVersion(tenantId, processDefinitionKey)
                || drafts.find(tenantId, processDefinitionKey).isPresent();
    }

    @Override
    public List<ProcessDefinitionView> list(String processDefinitionKey) {
        String tenantId = tenantProvider.current().tenantId();
        var query = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .orderByProcessDefinitionKey()
                .asc()
                .orderByProcessDefinitionVersion()
                .desc();
        if (!StringUtils.hasText(processDefinitionKey)) {
            return query.latestVersion().list().stream()
                    .map(definition -> toView(definition, definition.getId(), false))
                    .toList();
        }
        List<ProcessDefinition> versions = query.processDefinitionKey(processDefinitionKey).list();
        String latestId = versions.isEmpty() ? null : versions.getFirst().getId();
        return versions.stream().map(definition -> toView(definition, latestId, false)).toList();
    }

    @Override
    public ProcessDefinitionView getDefinition(String processDefinitionKey, Integer version) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition latest = lookup.latest(tenantId, processDefinitionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist"));
        ProcessDefinition definition = version == null
                ? latest
                : lookup.version(tenantId, processDefinitionKey, version)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                                "Process definition does not exist"));
        return toView(definition, latest.getId(), true);
    }

    private ProcessDefinitionView toView(ProcessDefinition definition, String latestId, boolean includeXml) {
        boolean active = !definition.isSuspended() && definition.getId().equals(latestId);
        return new ProcessDefinitionView(
                definition.getId(),
                definition.getKey(),
                definition.getName(),
                definition.getVersion(),
                definition.getDeploymentId(),
                deploymentTime(definition.getDeploymentId()),
                definition.getTenantId(),
                active,
                includeXml ? lookup.xml(definition) : null
        );
    }

    private OffsetDateTime deploymentTime(String deploymentId) {
        Deployment deployment = repositoryService.createDeploymentQuery()
                .deploymentId(deploymentId)
                .singleResult();
        if (deployment == null || deployment.getDeploymentTime() == null) {
            return null;
        }
        return deployment.getDeploymentTime().toInstant().atOffset(ZoneOffset.UTC);
    }
}
