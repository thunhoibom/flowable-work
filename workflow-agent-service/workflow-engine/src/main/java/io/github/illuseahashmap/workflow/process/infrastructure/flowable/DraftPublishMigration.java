package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.process.application.DraftPublishMigrationService;
import io.github.illuseahashmap.workflow.process.application.dto.DraftPublishMigrationResult;
import io.github.illuseahashmap.workflow.process.application.dto.DraftPublishMigrationResult.Item;
import io.github.illuseahashmap.workflow.process.application.dto.DraftPublishMigrationResult.Scenario;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.BpmnXmlHash;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Converts the current tenant from the {@code workflow_active_version} table to draft/publish.
 * Each key runs in its own transaction, so one failure does not stop the others.
 */
@Component
public class DraftPublishMigration implements DraftPublishMigrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DraftPublishMigration.class);

    private final RepositoryService repositoryService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate perKey;
    private final ProcessDraftRepository drafts;
    private final ProcessReleaseRepository releases;
    private final ProcessDefinitionLookup lookup;
    private final ProcessDeployer deployer;
    private final AssignmentRuleService assignmentRuleService;
    private final TenantProvider tenantProvider;

    public DraftPublishMigration(RepositoryService repositoryService, JdbcTemplate jdbc,
                                 PlatformTransactionManager transactionManager,
                                 ProcessDraftRepository drafts, ProcessReleaseRepository releases,
                                 ProcessDefinitionLookup lookup, ProcessDeployer deployer,
                                 AssignmentRuleService assignmentRuleService, TenantProvider tenantProvider) {
        this.repositoryService = repositoryService;
        this.jdbc = jdbc;
        this.perKey = new TransactionTemplate(transactionManager);
        this.perKey.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.drafts = drafts;
        this.releases = releases;
        this.lookup = lookup;
        this.deployer = deployer;
        this.assignmentRuleService = assignmentRuleService;
        this.tenantProvider = tenantProvider;
    }

    @Override
    public DraftPublishMigrationResult run(boolean dryRun) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        List<String> keys = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenant.tenantId()).latestVersion().list().stream()
                .map(ProcessDefinition::getKey).sorted().toList();
        List<Item> items = new ArrayList<>();
        for (String key : keys) {
            try {
                items.add(dryRun
                        ? new Item(key, scenario(tenant, key), "PLANNED", null, null)
                        : perKey.execute(status -> migrate(tenant, key)));
            } catch (RuntimeException exception) {
                LOGGER.warn("Draft/publish migration failed for {} in tenant {}", key, tenant.tenantId(), exception);
                items.add(new Item(key, null, "FAILED", null, exception.getMessage()));
            }
        }
        return new DraftPublishMigrationResult(dryRun, items);
    }

    private Item migrate(TenantContext.TenantInfo tenant, String key) {
        Scenario scenario = scenario(tenant, key);
        if (scenario == Scenario.ALREADY_MIGRATED) {
            return new Item(key, scenario, "SKIPPED", null, null);
        }
        ProcessDefinition latest = lookup.latest(tenant.tenantId(), key).orElseThrow();
        String latestXml = lookup.xml(latest);
        Integer newVersion = null;
        switch (scenario) {
            case ACTIVE_IS_LATEST -> deployer.record(tenant, latest, ReleaseAction.MIGRATED, null,
                    BpmnXmlHash.of(latestXml));
            case DRAFT_AHEAD -> {
                ProcessDefinition active = repositoryService.getProcessDefinition(
                        activeId(tenant, key).orElseThrow());
                String activeXml = lookup.xml(active);
                // No guards: what already runs must keep running, even if the rules got stricter.
                ProcessDefinition redeployed = deployer.deployUnchecked(tenant, key, active.getName(), activeXml);
                assignmentRuleService.copyRules(active.getId(), redeployed.getId());
                deployer.record(tenant, redeployed, ReleaseAction.MIGRATED, active.getVersion(),
                        BpmnXmlHash.of(activeXml));
                newVersion = redeployed.getVersion();
            }
            case NEVER_PUBLISHED -> {
                repositoryService.createProcessDefinitionQuery()
                        .processDefinitionTenantId(tenant.tenantId())
                        .processDefinitionKey(key)
                        .active()
                        .list()
                        .forEach(definition -> repositoryService.suspendProcessDefinitionById(
                                definition.getId(), false, null));
                deployer.record(tenant, latest, ReleaseAction.MIGRATED, null, BpmnXmlHash.of(latestXml));
            }
            default -> throw new IllegalStateException("Unexpected scenario " + scenario);
        }
        if (drafts.find(tenant.tenantId(), key).isEmpty()) {
            String name = StringUtils.hasText(latest.getName()) ? latest.getName() : key;
            drafts.insert(ProcessDraft.of(tenant.tenantId(), key, name, latestXml, 1, "migration",
                    OffsetDateTime.now(ZoneOffset.UTC)));
        }
        return new Item(key, scenario, "MIGRATED", newVersion, null);
    }

    private Scenario scenario(TenantContext.TenantInfo tenant, String key) {
        if (releases.hasAny(tenant.tenantId(), key)) {
            return Scenario.ALREADY_MIGRATED;
        }
        String latestId = lookup.latest(tenant.tenantId(), key).orElseThrow().getId();
        return activeId(tenant, key)
                .map(id -> id.equals(latestId) ? Scenario.ACTIVE_IS_LATEST : Scenario.DRAFT_AHEAD)
                .orElse(Scenario.NEVER_PUBLISHED);
    }

    private Optional<String> activeId(TenantContext.TenantInfo tenant, String key) {
        return jdbc.queryForList("""
                SELECT process_definition_id FROM workflow_active_version
                WHERE tenant_id = ? AND process_definition_key = ?
                """, String.class, tenant.tenantId(), key).stream().findFirst();
    }
}
