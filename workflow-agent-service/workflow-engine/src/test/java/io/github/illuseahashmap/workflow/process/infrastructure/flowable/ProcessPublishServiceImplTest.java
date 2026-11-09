package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import io.github.illuseahashmap.workflow.config.SubProcessProperties;
import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.domain.CallReference;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess.CallActivityDeploymentGuard;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Set;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessPublishServiceImplTest {

    private static final String TENANT = "tenant-publish";

    static String userTaskXml(String key, String... taskIds) {
        StringBuilder tasks = new StringBuilder();
        String previous = "s";
        for (String id : taskIds) {
            tasks.append("<userTask id=\"%s\" flowable:assignee=\"alice\"/>".formatted(id))
                    .append("<sequenceFlow id=\"f_%s\" sourceRef=\"%s\" targetRef=\"%s\"/>"
                            .formatted(id, previous, id));
            previous = id;
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="s"/>%s<endEvent id="e"/>
                    <sequenceFlow id="f_end" sourceRef="%s" targetRef="e"/>
                  </process>
                </definitions>""".formatted(key, key, tasks, previous);
    }

    static String timerStartXml(String key, String cycle) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"><timerEventDefinition><timeCycle>%s</timeCycle></timerEventDefinition></startEvent>
                    <endEvent id="e"/><sequenceFlow id="f" sourceRef="s" targetRef="e"/>
                  </process>
                </definitions>""".formatted(key, cycle);
    }

    private static ProcessEngine engine;
    private ProcessDraftServiceImpl drafts;
    private InMemoryProcessRepositories.Releases releases;
    private InMemoryProcessRepositories.References callReferences;
    private AssignmentRuleService rules;
    private AgentBindingDeploymentValidator agentValidator;
    private ProcessPublishServiceImpl service;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:publish-service;DB_CLOSE_DELAY=-1");
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
        TenantProvider tenant = () -> new TenantContext.TenantInfo(TENANT, "pub", "Pub");
        CurrentPrincipalProvider principal =
                () -> new CurrentPrincipal("USER", "u1", "alice", "Alice", "pub", Set.of(), Set.of());
        var draftRepository = new InMemoryProcessRepositories.Drafts();
        var lookup = new ProcessDefinitionLookup(engine.getRepositoryService());
        var inspector = new BpmnDraftInspector(new ProcessDefinitionProperties());
        releases = new InMemoryProcessRepositories.Releases();
        rules = mock(AssignmentRuleService.class);
        when(rules.copyRules(anyString(), anyString()))
                .thenAnswer(call -> new AssignmentRuleInheritResult(
                        call.getArgument(0), null, call.getArgument(1), null, 0, 0, List.of()));
        agentValidator = mock(AgentBindingDeploymentValidator.class);
        drafts = new ProcessDraftServiceImpl(draftRepository, lookup, inspector, tenant, principal);
        callReferences = new InMemoryProcessRepositories.References();
        SubProcessProperties subProcessProperties = new SubProcessProperties();
        subProcessProperties.setCallActivityEnabled(true);
        var deployer = new ProcessDeployer(engine.getRepositoryService(), releases, inspector,
                new ScriptTaskDeploymentGuard(new ScriptTaskProperties()),
                new SubProcessDeploymentGuard(subProcessProperties),
                new CallActivityDeploymentGuard(subProcessProperties, engine.getRepositoryService(), lookup,
                        callReferences, mock(FlowableParticipantAssignmentCoordinator.class)),
                new EventRegistryDeploymentGuard(),
                callReferences, mock(AgentTaskBindingParser.class), agentValidator, principal);
        service = new ProcessPublishServiceImpl(engine.getRepositoryService(), draftRepository, releases,
                lookup, inspector, deployer, rules, mock(WorkflowOperationAuditService.class), tenant, principal);
    }

    private static long definitions(String key) {
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT).processDefinitionKey(key).count();
    }

    @Test
    void publishDeploysTheDraftAndRecordsARelease() {
        drafts.create(new CreateProcessDraftRequest("p1", "P1", userTaskXml("p1", "a")));
        assertThat(definitions("p1")).isZero();

        var result = service.publish("p1", new PublishProcessRequest(1));

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.rules()).isNull();
        assertThat(result.newUserTaskIds()).containsExactly("a");
        assertThat(releases.list(TENANT, "p1")).extracting(ProcessRelease::action)
                .containsExactly(ReleaseAction.PUBLISH);
        assertThat(releases.list(TENANT, "p1").getFirst().performedBy()).isEqualTo("alice");
        assertThat(drafts.get("p1").hasUnpublishedChanges()).isFalse();
    }

    @Test
    void secondPublishCopiesRulesAndReportsOnlyNewTasks() {
        drafts.create(new CreateProcessDraftRequest("p2", "P2", userTaskXml("p2", "a")));
        var first = service.publish("p2", new PublishProcessRequest(1));
        drafts.save("p2", new SaveProcessDraftRequest("P2", userTaskXml("p2", "a", "b"), 1));

        var second = service.publish("p2", new PublishProcessRequest(2));

        assertThat(second.version()).isEqualTo(2);
        assertThat(second.newUserTaskIds()).containsExactly("b");
        verify(rules).copyRules(first.processDefinitionId(), second.processDefinitionId());
    }

    @Test
    void refusesAStaleOrUnchangedDraft() {
        drafts.create(new CreateProcessDraftRequest("p3", "P3", userTaskXml("p3", "a")));
        service.publish("p3", new PublishProcessRequest(1));

        assertThatThrownBy(() -> service.publish("p3", new PublishProcessRequest(1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("giống");
        assertThatThrownBy(() -> service.publish("p3", new PublishProcessRequest(7)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DRAFT_REVISION_CONFLICT));
        assertThat(definitions("p3")).isEqualTo(1);
    }

    @Test
    void guardFailureDeploysNothing() {
        String script = userTaskXml("p4", "a").replace("<endEvent id=\"e\"/>",
                "<scriptTask id=\"x\" scriptFormat=\"javascript\"><script>1</script></scriptTask><endEvent id=\"e\"/>");
        drafts.create(new CreateProcessDraftRequest("p4", "P4", script));

        assertThatThrownBy(() -> service.publish("p4", new PublishProcessRequest(1)))
                .isInstanceOf(BusinessException.class);
        assertThat(definitions("p4")).isZero();
        assertThat(releases.hasAny(TENANT, "p4")).isFalse();
        verify(agentValidator, never()).validate(anyString(), anyList());
    }

    @Test
    void publishingACallActivityRecordsItsReferenceAndBlocksCycles() {
        service.publishXml("pc", "PC", userTaskXml("pc", "a"));
        String caller = userTaskXml("pr", "a").replace("<endEvent id=\"e\"/>",
                "<callActivity id=\"callPc\" calledElement=\"pc\"/><endEvent id=\"e\"/>");

        var published = service.publishXml("pr", "PR", caller);

        assertThat(callReferences.findByDefinition(TENANT, published.processDefinitionId()))
                .extracting(CallReference::activityId, CallReference::calledKey)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("callPc", "pc"));
        String backCall = userTaskXml("pc", "a").replace("<endEvent id=\"e\"/>",
                "<callActivity id=\"callPr\" calledElement=\"pr\"/><endEvent id=\"e\"/>");
        assertThatThrownBy(() -> service.publishXml("pc", "PC", backCall))
                .isInstanceOf(BusinessException.class).hasMessageContaining("pc → pr → pc");
    }

    @Test
    void subProcessGuardFailureDeploysNothing() {
        String adhoc = userTaskXml("p9", "a").replace("<endEvent id=\"e\"/>",
                "<adHocSubProcess id=\"x\"><userTask id=\"y\"/></adHocSubProcess><endEvent id=\"e\"/>");
        drafts.create(new CreateProcessDraftRequest("p9", "P9", adhoc));

        assertThatThrownBy(() -> service.publish("p9", new PublishProcessRequest(1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not supported");
        assertThat(definitions("p9")).isZero();
        verify(agentValidator, never()).validate(anyString(), anyList());
    }

    @Test
    void suspendBlocksNewStartsAndResumeAllowsThem() {
        drafts.create(new CreateProcessDraftRequest("p5", "P5", userTaskXml("p5", "a")));
        var published = service.publish("p5", new PublishProcessRequest(1));

        service.suspend("p5");

        assertThat(engine.getRepositoryService().getProcessDefinition(published.processDefinitionId()).isSuspended())
                .isTrue();
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceById(published.processDefinitionId()))
                .isInstanceOf(FlowableException.class);
        assertThatThrownBy(() -> service.suspend("p5")).hasMessageContaining("already");

        service.resume("p5");

        assertThat(engine.getRuntimeService().startProcessInstanceById(published.processDefinitionId())).isNotNull();
        assertThat(releases.list(TENANT, "p5")).extracting(ProcessRelease::action)
                .containsExactly(ReleaseAction.RESUME, ReleaseAction.SUSPEND, ReleaseAction.PUBLISH);
    }

    @Test
    void rollbackRedeploysTheOldXmlAsANewVersion() {
        drafts.create(new CreateProcessDraftRequest("p6", "P6", userTaskXml("p6", "a")));
        var v1 = service.publish("p6", new PublishProcessRequest(1));
        drafts.save("p6", new SaveProcessDraftRequest("P6", userTaskXml("p6", "a", "b"), 1));
        service.publish("p6", new PublishProcessRequest(2));

        var v3 = service.rollback("p6", new RollbackProcessRequest(1));

        assertThat(v3.version()).isEqualTo(3);
        assertThat(engine.getRepositoryService().getBpmnModel(v3.processDefinitionId())
                .getMainProcess().getFlowElement("b")).isNull();
        verify(rules).copyRules(v1.processDefinitionId(), v3.processDefinitionId());
        assertThat(releases.list(TENANT, "p6").getFirst().sourceVersion()).isEqualTo(1);
        assertThatThrownBy(() -> service.rollback("p6", new RollbackProcessRequest(3)))
                .hasMessageContaining("latest");
    }

    @Test
    void timerStartRunsOnlyAfterPublish() {
        drafts.create(new CreateProcessDraftRequest("p7", "P7", timerStartXml("p7", "R/PT1H")));
        assertThat(engine.getManagementService().createTimerJobQuery().jobTenantId(TENANT).count()).isZero();

        service.publish("p7", new PublishProcessRequest(1));
        drafts.save("p7", new SaveProcessDraftRequest("P7", timerStartXml("p7", "R/PT2H"), 1));

        assertThat(engine.getManagementService().createTimerJobQuery().jobTenantId(TENANT).count()).isEqualTo(1);
        assertThat(definitions("p7")).isEqualTo(1);
    }

    @Test
    void publishXmlIsIdempotentForUnchangedXml() {
        var first = service.publishXml("p8", "P8", userTaskXml("p8", "a"));
        var again = service.publishXml("p8", "P8", userTaskXml("p8", "a"));
        var changed = service.publishXml("p8", "P8", userTaskXml("p8", "a", "b"));

        assertThat(again.processDefinitionId()).isEqualTo(first.processDefinitionId());
        assertThat(changed.version()).isEqualTo(2);
        assertThat(definitions("p8")).isEqualTo(2);
    }
}
