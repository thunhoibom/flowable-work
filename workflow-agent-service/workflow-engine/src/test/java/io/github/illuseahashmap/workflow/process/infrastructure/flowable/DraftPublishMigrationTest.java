package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.dto.DraftPublishMigrationResult;
import io.github.illuseahashmap.workflow.process.application.dto.DraftPublishMigrationResult.Scenario;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DraftPublishMigrationTest {

    private static final String TENANT = "tenant-migrate";
    private static final String URL = "jdbc:h2:mem:migration;DB_CLOSE_DELAY=-1";

    private static ProcessEngine engine;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl(URL);
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, "sa", ""));
        jdbc.execute("""
                CREATE TABLE workflow_active_version (
                    tenant_id VARCHAR(64), process_definition_key VARCHAR(128),
                    process_definition_id VARCHAR(128), version INT,
                    activated_by VARCHAR(128), activated_at TIMESTAMP)""");
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static ProcessDefinition deploy(String key, String marker) {
        var deployment = engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", ProcessDraftServiceImplTest.xml(key, marker)).deploy();
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
    }

    private static void activate(ProcessDefinition definition) {
        jdbc.update("INSERT INTO workflow_active_version VALUES (?, ?, ?, ?, 'admin', CURRENT_TIMESTAMP)",
                TENANT, definition.getKey(), definition.getId(), definition.getVersion());
    }

    private static long count(String key) {
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT).processDefinitionKey(key).count();
    }

    private static Map<String, DraftPublishMigrationResult.Item> byKey(DraftPublishMigrationResult result) {
        return result.items().stream().collect(Collectors.toMap(
                DraftPublishMigrationResult.Item::processDefinitionKey, item -> item));
    }

    @Test
    void migratesEachScenarioOnceAndIsIdempotent() {
        ProcessDefinition m1 = deploy("m1", "only");
        activate(m1);
        ProcessDefinition m2v1 = deploy("m2", "live");
        deploy("m2", "wip");
        activate(m2v1);
        ProcessDefinition m3 = deploy("m3", "never");

        var drafts = new InMemoryProcessRepositories.Drafts();
        var releases = new InMemoryProcessRepositories.Releases();
        var rules = mock(AssignmentRuleService.class);
        var lookup = new ProcessDefinitionLookup(engine.getRepositoryService());
        var deployer = new ProcessDeployer(engine.getRepositoryService(), releases,
                new BpmnDraftInspector(new ProcessDefinitionProperties()),
                new ScriptTaskDeploymentGuard(new ScriptTaskProperties()),
                mock(AgentTaskBindingParser.class), mock(AgentBindingDeploymentValidator.class),
                () -> new CurrentPrincipal("USER", "u1", "admin", "Admin", "mig", Set.of(), Set.of()));
        var migration = new DraftPublishMigration(engine.getRepositoryService(), jdbc,
                new DataSourceTransactionManager(jdbc.getDataSource()), drafts, releases, lookup, deployer, rules,
                () -> new TenantContext.TenantInfo(TENANT, "mig", "Mig"));

        var plan = byKey(migration.run(true));
        assertThat(plan.get("m1").scenario()).isEqualTo(Scenario.ACTIVE_IS_LATEST);
        assertThat(plan.get("m2").scenario()).isEqualTo(Scenario.DRAFT_AHEAD);
        assertThat(plan.get("m3").scenario()).isEqualTo(Scenario.NEVER_PUBLISHED);
        assertThat(count("m2")).isEqualTo(2);
        assertThat(drafts.find(TENANT, "m1")).isEmpty();
        assertThat(releases.hasAny(TENANT, "m1")).isFalse();

        var applied = byKey(migration.run(false));
        assertThat(applied.values()).allSatisfy(item -> assertThat(item.error()).isNull());

        // m1: nothing redeployed, draft equals the running XML.
        assertThat(count("m1")).isEqualTo(1);
        assertThat(drafts.find(TENANT, "m1").orElseThrow().bpmnXml()).contains("only");
        assertThat(releases.list(TENANT, "m1")).extracting(ProcessRelease::action)
                .containsExactly(ReleaseAction.MIGRATED);

        // m2: the live XML becomes v3 on top, the work in progress stays in the draft.
        assertThat(applied.get("m2").newVersion()).isEqualTo(3);
        ProcessDefinition m2Latest = lookup.latest(TENANT, "m2").orElseThrow();
        assertThat(lookup.xml(m2Latest)).contains("live");
        assertThat(drafts.find(TENANT, "m2").orElseThrow().bpmnXml()).contains("wip");
        verify(rules).copyRules(m2v1.getId(), m2Latest.getId());
        assertThat(releases.list(TENANT, "m2").getFirst().sourceVersion()).isEqualTo(1);

        // m3: never published, so nothing can start it any more.
        assertThat(engine.getRepositoryService().getProcessDefinition(m3.getId()).isSuspended()).isTrue();
        assertThat(drafts.find(TENANT, "m3")).isPresent();

        var again = byKey(migration.run(false));
        assertThat(again.values()).allSatisfy(item ->
                assertThat(item.scenario()).isEqualTo(Scenario.ALREADY_MIGRATED));
        assertThat(count("m1")).isEqualTo(1);
        assertThat(count("m2")).isEqualTo(3);
        verify(rules, never()).copyRules(anyString(), org.mockito.ArgumentMatchers.eq(m1.getId()));
    }
}
