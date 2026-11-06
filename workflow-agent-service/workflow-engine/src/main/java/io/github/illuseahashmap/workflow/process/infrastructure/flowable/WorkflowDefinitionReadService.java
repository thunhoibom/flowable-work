package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionDiagramView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraftHeader;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** List and diagram reads. A key is published when its latest definition is not suspended. */
@Component
class WorkflowDefinitionReadService {

    static final String PUBLISHED = "published";
    static final String SUSPENDED = "suspended";
    static final String DRAFT_ONLY = "draft_only";

    private final RepositoryService repositoryService;
    private final TenantProvider tenantProvider;
    private final ProcessDraftRepository drafts;
    private final ProcessReleaseRepository releases;
    private final ProcessDefinitionLookup lookup;

    WorkflowDefinitionReadService(RepositoryService repositoryService,
                                  TenantProvider tenantProvider,
                                  ProcessDraftRepository drafts,
                                  ProcessReleaseRepository releases,
                                  ProcessDefinitionLookup lookup) {
        this.repositoryService = repositoryService;
        this.tenantProvider = tenantProvider;
        this.drafts = drafts;
        this.releases = releases;
        this.lookup = lookup;
    }

    PageResult<ProcessDefinitionSummaryView> page(
            Integer pageNum, Integer pageSize, String key, String name, String publishStatus) {
        int normalizedPage = normalizePage(pageNum);
        int normalizedSize = normalizeSize(pageSize);
        String normalizedStatus = normalizeStatus(publishStatus);
        String tenantId = tenantProvider.current().tenantId();
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .latestVersion()
                .list();
        Map<String, ProcessDraftHeader> draftsByKey = drafts.listHeaders(tenantId).stream()
                .collect(Collectors.toMap(ProcessDraftHeader::processDefinitionKey, Function.identity()));
        Map<String, ProcessRelease> releaseByDefinition = releases.latestByDefinition(tenantId);
        Map<String, OffsetDateTime> deploymentTimes = deploymentTimes(definitions.stream()
                .map(ProcessDefinition::getDeploymentId)
                .collect(Collectors.toSet()));

        List<ProcessDefinitionSummaryView> summaries = new ArrayList<>();
        for (ProcessDefinition definition : definitions) {
            summaries.add(toSummary(definition, releaseByDefinition.get(definition.getId()),
                    draftsByKey.remove(definition.getKey()), deploymentTimes));
        }
        draftsByKey.values().forEach(draft -> summaries.add(draftOnly(draft, tenantId)));

        List<ProcessDefinitionSummaryView> filtered = summaries.stream()
                .filter(summary -> matches(summary.processDefinitionKey(), key))
                .filter(summary -> matches(summary.processDefinitionName(), name))
                .filter(summary -> statusMatches(normalizedStatus, summary.publishStatus()))
                .sorted(Comparator.comparing(
                        ProcessDefinitionSummaryView::latestDeployTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return slice(filtered, normalizedPage, normalizedSize);
    }

    ProcessDefinitionDiagramView diagram(String key, Integer version, String processDefinitionId) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition definition = resolve(tenantId, key, version, processDefinitionId);
        boolean active = !definition.isSuspended() && lookup.latest(tenantId, definition.getKey())
                .map(latest -> latest.getId().equals(definition.getId()))
                .orElse(false);
        return new ProcessDefinitionDiagramView(
                definition.getId(), definition.getKey(), definition.getName(), definition.getVersion(),
                definition.getDeploymentId(), deploymentTime(definition.getDeploymentId()),
                active, lookup.xml(definition), definition.getTenantId());
    }

    String readBpmnXml(String processDefinitionId) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        return lookup.xml(definition);
    }

    private ProcessDefinitionSummaryView toSummary(ProcessDefinition definition, ProcessRelease release,
                                                   ProcessDraftHeader draft,
                                                   Map<String, OffsetDateTime> deploymentTimes) {
        boolean running = !definition.isSuspended();
        boolean changed = draft != null && release != null && !draft.xmlSha256().equals(release.xmlSha256());
        return new ProcessDefinitionSummaryView(
                definition.getId(), definition.getKey(), definition.getName(), definition.getVersion(),
                definition.getDeploymentId(), deploymentTimes.get(definition.getDeploymentId()),
                running ? definition.getVersion() : null,
                running ? definition.getId() : null,
                running ? definition.getDeploymentId() : null,
                release == null ? null : release.performedAt(),
                running ? PUBLISHED : SUSPENDED,
                definition.getTenantId(),
                changed);
    }

    private static ProcessDefinitionSummaryView draftOnly(ProcessDraftHeader draft, String tenantId) {
        return new ProcessDefinitionSummaryView(
                null, draft.processDefinitionKey(), draft.name(), 0, null, draft.updatedAt(),
                null, null, null, null, DRAFT_ONLY, tenantId, true);
    }

    private static boolean matches(String value, String filter) {
        if (!StringUtils.hasText(filter)) {
            return true;
        }
        return value != null && value.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
    }

    /** {@code unpublished} is kept for older clients and means "not running". */
    private static boolean statusMatches(String filter, String status) {
        return switch (filter) {
            case "all" -> true;
            case "unpublished" -> !PUBLISHED.equals(status);
            default -> filter.equals(status);
        };
    }

    private Map<String, OffsetDateTime> deploymentTimes(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repositoryService.createDeploymentQuery().deploymentIds(List.copyOf(ids)).list().stream()
                .collect(Collectors.toMap(Deployment::getId,
                        deployment -> toOffsetDateTime(deployment.getDeploymentTime())));
    }

    /** No version and no id: the latest version, suspended or not, so a stopped process can still be viewed. */
    private ProcessDefinition resolve(String tenantId, String key, Integer version, String definitionId) {
        ProcessDefinition definition;
        if (StringUtils.hasText(definitionId)) {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionTenantId(tenantId)
                    .processDefinitionId(definitionId.trim())
                    .singleResult();
            if (definition != null && StringUtils.hasText(key) && !key.trim().equals(definition.getKey())) {
                definition = null;
            }
        } else if (version != null) {
            definition = lookup.version(tenantId, key, version).orElse(null);
        } else {
            definition = lookup.latest(tenantId, key).orElse(null);
        }
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        return definition;
    }

    private OffsetDateTime deploymentTime(String deploymentId) {
        Deployment deployment = repositoryService.createDeploymentQuery().deploymentId(deploymentId).singleResult();
        return deployment == null ? null : toOffsetDateTime(deployment.getDeploymentTime());
    }

    private OffsetDateTime toOffsetDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "all";
        if (!Set.of("all", PUBLISHED, SUSPENDED, DRAFT_ONLY, "unpublished").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported status: " + status);
        }
        return normalized;
    }

    private int normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
    }

    private <T> PageResult<T> slice(List<T> source, int pageNum, int pageSize) {
        int fromIndex = Math.min((pageNum - 1) * pageSize, source.size());
        int toIndex = Math.min(fromIndex + pageSize, source.size());
        return new PageResult<>(source.size(), pageNum, pageSize, source.subList(fromIndex, toIndex));
    }
}
