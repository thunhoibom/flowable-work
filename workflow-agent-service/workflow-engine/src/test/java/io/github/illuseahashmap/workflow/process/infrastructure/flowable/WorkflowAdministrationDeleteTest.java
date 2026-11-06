package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkflowAdministrationDeleteTest {

    private static final String TENANT = "tenant-delete";
    private static ProcessEngine engine;

    private InMemoryProcessRepositories.Drafts drafts;
    private InMemoryProcessRepositories.Releases releases;
    private AssignmentRuleService rules;
    private WorkflowAdministrationServiceImpl service;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:admin-delete;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        drafts = new InMemoryProcessRepositories.Drafts();
        releases = new InMemoryProcessRepositories.Releases();
        rules = mock(AssignmentRuleService.class);
        service = new WorkflowAdministrationServiceImpl(engine.getRepositoryService(), engine.getRuntimeService(),
                engine.getHistoryService(), mock(JdbcTemplate.class), mock(ProcessInstanceTransactionExecutor.class),
                null, null, () -> new TenantContext.TenantInfo(TENANT, "del", "Del"), rules,
                mock(WorkflowOperationAuditService.class), drafts, releases);
    }

    private static ProcessDefinition deploy(String key) {
        var deployment = engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", ProcessPublishServiceImplTest.userTaskXml(key, "a")).deploy();
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
    }

    private static long count(String key) {
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT).processDefinitionKey(key).count();
    }

    @Test
    void deleteVersionRejectsTheLatest() {
        deploy("x1");
        deploy("x1");

        assertThatThrownBy(() -> service.deleteProcessDefinitionVersion("x1", 2))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("ngừng phát hành");
    }

    @Test
    void deleteVersionRejectsRunningInstances() {
        ProcessDefinition v1 = deploy("x2");
        deploy("x2");
        engine.getRuntimeService().startProcessInstanceById(v1.getId());

        assertThatThrownBy(() -> service.deleteProcessDefinitionVersion("x2", 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(count("x2")).isEqualTo(2);
    }

    @Test
    void deleteVersionRemovesAnIdleOldVersion() {
        ProcessDefinition v1 = deploy("x3");
        deploy("x3");

        service.deleteProcessDefinitionVersion("x3", 1);

        assertThat(count("x3")).isEqualTo(1);
        verify(rules).deleteByProcessDefinition(v1.getId());
    }

    @Test
    void deleteKeyRejectsRunningInstances() {
        ProcessDefinition v1 = deploy("x4");
        engine.getRuntimeService().startProcessInstanceById(v1.getId());

        assertThatThrownBy(() -> service.deleteProcessDefinitions("x4"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(count("x4")).isEqualTo(1);
    }

    @Test
    void deleteKeyRemovesDraftAndReleases() {
        ProcessDefinition v1 = deploy("x5");
        drafts.insert(ProcessDraft.of(TENANT, "x5", "X5", "<a/>", 1, "alice", OffsetDateTime.now()));
        releases.append(new ProcessRelease(null, TENANT, "x5", v1.getId(), 1, ReleaseAction.PUBLISH, null,
                "0".repeat(64), "alice", OffsetDateTime.now()));

        service.deleteProcessDefinitions("x5");

        assertThat(count("x5")).isZero();
        assertThat(drafts.find(TENANT, "x5")).isEmpty();
        assertThat(releases.hasAny(TENANT, "x5")).isFalse();
        verify(rules).deleteByProcessDefinitionKey("x5");
    }

    @Test
    void deleteKeyRemovesADraftOnlyProcess() {
        drafts.insert(ProcessDraft.of(TENANT, "x6", "X6", "<a/>", 1, "alice", OffsetDateTime.now()));

        service.deleteProcessDefinitions("x6");

        assertThat(drafts.find(TENANT, "x6")).isEmpty();
        assertThatThrownBy(() -> service.deleteProcessDefinitions("x6"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
