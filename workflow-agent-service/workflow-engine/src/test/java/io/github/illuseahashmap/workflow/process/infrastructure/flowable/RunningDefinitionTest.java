package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;

import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Set;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** "Running" means the latest definition of the key that is not suspended. */
class RunningDefinitionTest {

    private static final String TENANT = "tenant-running";
    private static final TenantProvider TENANT_PROVIDER = () -> new TenantContext.TenantInfo(TENANT, "run", "Run");

    private static ProcessEngine engine;
    private static InMemoryProcessRepositories.Drafts drafts;
    private static WorkflowDefinitionServiceImpl definitions;
    private static WorkflowTaskOperationSupport support;
    private static WorkflowDefinitionReadService reads;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:running;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        drafts = new InMemoryProcessRepositories.Drafts();
        var releases = new InMemoryProcessRepositories.Releases();
        var lookup = new ProcessDefinitionLookup(engine.getRepositoryService());
        definitions = new WorkflowDefinitionServiceImpl(engine.getRepositoryService(), TENANT_PROVIDER,
                mock(ProcessPublishService.class), releases, drafts, lookup);
        support = new WorkflowTaskOperationSupport(engine.getRuntimeService(), engine.getTaskService(),
                engine.getRepositoryService(), definitions, null, null, TENANT_PROVIDER);
        reads = new WorkflowDefinitionReadService(engine.getRepositoryService(), TENANT_PROVIDER,
                drafts, releases, lookup);
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static ProcessDefinition deploy(String key) {
        var deployment = engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", ProcessDraftServiceImplTest.xml(key, key)).deploy();
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
    }

    @Test
    void startByKeyUsesLatestAndRejectsSuspended() {
        deploy("r1");
        ProcessDefinition v2 = deploy("r1");

        assertThat(support.resolveProcessDefinitionId("r1", null, TENANT)).isEqualTo(v2.getId());

        engine.getRepositoryService().suspendProcessDefinitionById(v2.getId(), false, null);
        assertThatThrownBy(() -> support.resolveProcessDefinitionId("r1", null, TENANT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void startByIdAcceptsOnlyTheLatest() {
        ProcessDefinition v1 = deploy("r2");
        ProcessDefinition v2 = deploy("r2");

        assertThat(support.resolveProcessDefinitionId("r2", v2.getId(), TENANT)).isEqualTo(v2.getId());
        assertThatThrownBy(() -> support.resolveProcessDefinitionId("r2", v1.getId(), TENANT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void activeFlagFollowsLatestAndSuspension() {
        deploy("r3");
        ProcessDefinition v2 = deploy("r3");

        assertThat(definitions.list("r3")).extracting("version", "active")
                .containsExactly(tuple(2, true), tuple(1, false));
        assertThat(definitions.getActiveVersion("r3").version()).isEqualTo(2);

        engine.getRepositoryService().suspendProcessDefinitionById(v2.getId(), false, null);
        assertThat(definitions.list("r3").getFirst().active()).isFalse();
        assertThatThrownBy(() -> definitions.getActiveVersion("r3"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void pageShowsDraftOnlyPublishedAndSuspendedKeys() {
        deploy("r4");
        ProcessDefinition stopped = deploy("r5");
        engine.getRepositoryService().suspendProcessDefinitionById(stopped.getId(), false, null);
        drafts.insert(ProcessDraft.of(TENANT, "r6", "Chỉ nháp", ProcessDraftServiceImplTest.xml("r6", "x"), 1,
                "alice", OffsetDateTime.now()));

        var page = reads.page(1, 100, "r", null, "all").records().stream()
                .filter(row -> Set.of("r4", "r5", "r6").contains(row.processDefinitionKey()))
                .toList();

        assertThat(page).extracting(ProcessDefinitionSummaryView::processDefinitionKey,
                        ProcessDefinitionSummaryView::publishStatus)
                .containsExactlyInAnyOrder(tuple("r4", "published"), tuple("r5", "suspended"),
                        tuple("r6", "draft_only"));
        assertThat(reads.page(1, 100, "r6", null, "draft_only").records()).hasSize(1);
        assertThat(reads.page(1, 100, "r5", null, "unpublished").records()).hasSize(1);
        assertThat(definitions.exists("r6")).isTrue();
    }
}
