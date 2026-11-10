package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.TerminateProcessRequest;
import io.github.illuseahashmap.workflow.process.domain.WorkflowOperationAudit;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess.SubProcessAuditListener;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reading, terminating and auditing a parent → child → grandchild call tree. */
class SubProcessOperationsTest {

    private static final String TENANT = "tenant-tree";
    private static final TenantProvider TENANT_PROVIDER = () -> new TenantContext.TenantInfo(TENANT, "tree", "Tree");
    private static final List<WorkflowOperationAudit> AUDITS = new ArrayList<>();
    private static ProcessEngine engine;

    private WorkflowInstanceReadService reads;
    private WorkflowAdministrationServiceImpl administration;
    private WorkflowOperationAuditService auditService;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:subprocess-operations;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        configuration.setEventListeners(List.of(new SubProcessAuditListener(() -> AUDITS::add)));
        engine = configuration.buildProcessEngine();
        deploy("grandchild", "<userTask id=\"leaf\"/>");
        deploy("child", "<callActivity id=\"callGrandchild\" name=\"Gọi cháu\" calledElement=\"grandchild\"/>");
        deploy("parent", "<callActivity id=\"callChild\" name=\"Gọi con\" calledElement=\"child\"/>");
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        AUDITS.clear();
        CurrentPrincipalContext.set(new CurrentPrincipal("USER", "u1", "alice", "Alice", "tree", Set.of(), Set.of()));
        auditService = mock(WorkflowOperationAuditService.class);
        reads = new WorkflowInstanceReadService(engine.getRepositoryService(), engine.getRuntimeService(),
                engine.getHistoryService(), engine.getTaskService(), engine, mock(AssignmentRuleService.class),
                null, TENANT_PROVIDER);
        ProcessInstanceTransactionExecutor executor = mock(ProcessInstanceTransactionExecutor.class);
        Mockito.when(executor.execute(anyString(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        administration = new WorkflowAdministrationServiceImpl(engine.getRepositoryService(),
                engine.getRuntimeService(), engine.getHistoryService(), mock(JdbcTemplate.class), executor,
                null, reads, TENANT_PROVIDER, mock(AssignmentRuleService.class), auditService,
                new InMemoryProcessRepositories.Drafts(), new InMemoryProcessRepositories.Releases(),
                new InMemoryProcessRepositories.References(), mock(EventReferenceRepository.class));
    }

    @AfterEach
    void clearPrincipal() {
        CurrentPrincipalContext.clear();
    }

    private static void deploy(String key, String step) {
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                          <process id="%s" name="%s" isExecutable="true">
                            <startEvent id="s"/>%s<endEvent id="e"/>
                            <sequenceFlow id="f1" sourceRef="s" targetRef="%s"/>
                            <sequenceFlow id="f2" sourceRef="%s" targetRef="e"/>
                          </process>
                        </definitions>""".formatted(key, key, step, idOf(step), idOf(step))).deploy();
    }

    private static String idOf(String step) {
        return step.replaceAll(".*id=\"([^\"]+)\".*", "$1");
    }

    private record Tree(ProcessInstance parent, ProcessInstance child, ProcessInstance grandchild) {
    }

    private static Tree startTree() {
        ProcessInstance parent = engine.getRuntimeService()
                .startProcessInstanceByKeyAndTenantId("parent", Map.of(), TENANT);
        ProcessInstance child = engine.getRuntimeService().createProcessInstanceQuery()
                .superProcessInstanceId(parent.getId()).singleResult();
        ProcessInstance grandchild = engine.getRuntimeService().createProcessInstanceQuery()
                .superProcessInstanceId(child.getId()).singleResult();
        return new Tree(parent, child, grandchild);
    }

    @Test
    void detailShowsAncestorsCallActivityAndChildren() {
        Tree tree = startTree();

        var grandchild = reads.detail(tree.grandchild().getId());
        assertThat(grandchild.ancestors()).extracting("processInstanceId")
                .containsExactly(tree.parent().getId(), tree.child().getId());
        assertThat(grandchild.callActivityId()).isEqualTo("callGrandchild");
        assertThat(grandchild.instance().superProcessInstanceId()).isEqualTo(tree.child().getId());

        var parent = reads.detail(tree.parent().getId());
        assertThat(parent.ancestors()).isEmpty();
        assertThat(parent.callActivityId()).isNull();
        assertThat(parent.children()).singleElement().satisfies(child -> {
            assertThat(child.callActivityId()).isEqualTo("callChild");
            assertThat(child.callActivityName()).isEqualTo("Gọi con");
            assertThat(child.instance().processInstanceId()).isEqualTo(tree.child().getId());
            assertThat(child.instance().processDefinitionKey()).isEqualTo("child");
        });
    }

    @Test
    void rootOnlyHidesChildren() {
        Tree tree = startTree();

        var all = reads.page(1, 100, null, null, null, null, "running", false).records();
        var roots = reads.page(1, 100, null, null, null, null, "running", true).records();

        assertThat(all).extracting("processInstanceId").contains(tree.child().getId(), tree.parent().getId());
        assertThat(roots).extracting("processInstanceId")
                .contains(tree.parent().getId())
                .doesNotContain(tree.child().getId(), tree.grandchild().getId());
    }

    @Test
    void aChildCannotBeTerminatedOnItsOwn() {
        Tree tree = startTree();

        assertThatThrownBy(() -> administration.terminateProcessInstance(
                new TerminateProcessRequest(tree.child().getId(), "test")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining(tree.parent().getId());
        assertThat(engine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(tree.child().getId()).count()).isEqualTo(1);
    }

    @Test
    void terminatingTheRootAuditsEveryDescendant() {
        Tree tree = startTree();

        administration.terminateProcessInstance(new TerminateProcessRequest(tree.parent().getId(), "stop"));

        assertThat(engine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceTenantId(TENANT).processInstanceId(tree.grandchild().getId()).count()).isZero();
        verify(auditService).record(eq("PROCESS_TERMINATED"), eq(tree.child().getId()), eq("child"),
                any(), any(), eq("RUNNING"), eq("TERMINATED"), eq("cascade from " + tree.parent().getId()));
        verify(auditService).record(eq("PROCESS_TERMINATED"), eq(tree.grandchild().getId()), eq("grandchild"),
                any(), any(), eq("RUNNING"), eq("TERMINATED"), eq("cascade from " + tree.parent().getId()));
    }

    @Test
    void auditsSubprocessStartAndCompletionInsideARequest() {
        Tree tree = startTree();
        assertThat(AUDITS).extracting(WorkflowOperationAudit::eventType)
                .containsExactly("SUBPROCESS_STARTED", "SUBPROCESS_STARTED");
        assertThat(AUDITS.get(1).subject())
                .isEqualTo("parent=" + tree.child().getId() + ", root=" + tree.parent().getId());

        var leaf = engine.getTaskService().createTaskQuery().processInstanceId(tree.grandchild().getId()).singleResult();
        engine.getTaskService().complete(leaf.getId());

        assertThat(AUDITS).extracting(WorkflowOperationAudit::eventType)
                .containsExactly("SUBPROCESS_STARTED", "SUBPROCESS_STARTED",
                        "SUBPROCESS_COMPLETED", "SUBPROCESS_COMPLETED");
        assertThat(AUDITS).allSatisfy(audit -> assertThat(audit.actorUsername()).isEqualTo("alice"));
    }

    @Test
    void skipsAuditOutsideARequest() {
        CurrentPrincipalContext.clear();

        startTree();

        assertThat(AUDITS).isEmpty();
    }
}
