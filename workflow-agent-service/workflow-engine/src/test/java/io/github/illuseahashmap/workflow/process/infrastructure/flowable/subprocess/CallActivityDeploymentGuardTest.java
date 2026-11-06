package io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.config.SubProcessProperties;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantRequirementView;
import io.github.illuseahashmap.workflow.process.domain.CallReference;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.FlowableParticipantAssignmentCoordinator;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.ProcessDefinitionLookup;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.InMemoryProcessRepositories;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.List;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallActivityDeploymentGuardTest {

    private static final String TENANT = "tenant-call-guard";
    private static final TenantContext.TenantInfo TENANT_INFO = new TenantContext.TenantInfo(TENANT, "cg", "CG");
    private static ProcessEngine engine;

    private InMemoryProcessRepositories.References references;
    private FlowableParticipantAssignmentCoordinator coordinator;
    private SubProcessProperties properties;
    private CallActivityDeploymentGuard guard;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:call-guard;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        for (String key : List.of("payment", "shipping", "a", "b", "c", "d", "stopped")) {
            deploy(key, "<startEvent id=\"s\"/><endEvent id=\"e\"/><sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/>");
        }
        ProcessDefinition stopped = latest("stopped");
        engine.getRepositoryService().suspendProcessDefinitionById(stopped.getId(), false, null);
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        references = new InMemoryProcessRepositories.References();
        coordinator = mock(FlowableParticipantAssignmentCoordinator.class);
        properties = new SubProcessProperties();
        properties.setCallActivityEnabled(true);
        properties.setMaxCallDepth(3);
        guard = new CallActivityDeploymentGuard(properties, engine.getRepositoryService(),
                new ProcessDefinitionLookup(engine.getRepositoryService()), references, coordinator);
    }

    private static void deploy(String key, String body) {
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", bpmn(key, body)).deploy();
    }

    private static ProcessDefinition latest(String key) {
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT).processDefinitionKey(key).latestVersion().singleResult();
    }

    static String bpmn(String key, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">%s</process>
                </definitions>""".formatted(key, body);
    }

    static String call(String attributes, String extensions) {
        return """
                <startEvent id="s"/>
                <callActivity id="call" %s>
                  <extensionElements>%s</extensionElements>
                </callActivity>
                <endEvent id="e"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <sequenceFlow id="f2" sourceRef="call" targetRef="e"/>""".formatted(attributes, extensions);
    }

    private List<CallActivityDeploymentGuard.Call> check(String key, String body) {
        return guard.check(TENANT_INFO, key, bpmn(key, body));
    }

    private void rejects(String key, String body, String message) {
        assertThatThrownBy(() -> check(key, body))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(message);
    }

    /** Publishes {@code parent → called} as the latest version of parent. */
    private void published(String parent, String called) {
        ProcessDefinition definition = latest(parent);
        references.replaceForDefinition(TENANT, definition.getId(), List.of(new CallReference(
                definition.getId(), parent, definition.getVersion(), "call", called)));
    }

    @Test
    void acceptsAValidCallAndReturnsItsReferences() {
        var calls = check("order", call("calledElement=\"payment\" flowable:inheritBusinessKey=\"true\"", """
                <flowable:in source="orderId" target="orderId"/>
                <flowable:in sourceExpression="${amount * 2}" target="doubled"/>
                <flowable:out source="status" target="paymentStatus"/>"""));

        assertThat(calls).containsExactly(new CallActivityDeploymentGuard.Call("call", "payment"));
    }

    @Test
    void ignoresDocumentsWithoutCallActivitiesEvenWhenDisabled() {
        properties.setCallActivityEnabled(false);

        assertThat(check("order", "<startEvent id=\"s\"/>")).isEmpty();
    }

    @Test
    void rejectsCallActivitiesWhileTheFeatureIsDisabled() {
        properties.setCallActivityEnabled(false);

        rejects("order", call("calledElement=\"payment\"", ""), "disabled");
    }

    @Test
    void rejectsUnsafeTargets() {
        rejects("order", call("", ""), "calledElement");
        rejects("order", call("calledElement=\"${next}\"", ""), "calledElement");
        rejects("order", call("calledElement=\"payment\" flowable:calledElementType=\"id\"", ""), "calledElementType");
        rejects("order", call("calledElement=\"missing\"", ""), "not published");
        rejects("order", call("calledElement=\"stopped\"", ""), "not published");
    }

    @Test
    void rejectsOptionsThatBypassTenantOrVersionRules() {
        rejects("order", call("calledElement=\"payment\" flowable:inheritVariables=\"true\"", ""), "inheritVariables");
        rejects("order", call("calledElement=\"payment\" flowable:fallbackToDefaultTenant=\"true\"", ""),
                "fallbackToDefaultTenant");
        rejects("order", call("calledElement=\"payment\" flowable:sameDeployment=\"true\"", ""), "sameDeployment");
        rejects("order", call("calledElement=\"payment\" flowable:calledElementBinding=\"deployment\"", ""),
                "calledElementBinding");
    }

    @Test
    void rejectsInvalidParameters() {
        rejects("order", call("calledElement=\"payment\"", "<flowable:in source=\"x\"/>"), "target");
        rejects("order", call("calledElement=\"payment\"",
                "<flowable:in source=\"x\" sourceExpression=\"${y}\" target=\"z\"/>"), "exactly one");
        rejects("order", call("calledElement=\"payment\"", "<flowable:in target=\"z\"/>"), "exactly one");
        rejects("order", call("calledElement=\"payment\"", "<flowable:in source=\"x\" target=\"tenantId\"/>"),
                "reserved");
        rejects("order", call("calledElement=\"payment\"", "<flowable:out source=\"x\" target=\"review_assignee\"/>"),
                "reserved");
        rejects("order", call("calledElement=\"payment\"",
                "<flowable:in source=\"x\" target=\"z\"/><flowable:in source=\"y\" target=\"z\"/>"), "twice");
        properties.setMaxIoParameters(1);
        rejects("order", call("calledElement=\"payment\"",
                "<flowable:in source=\"x\" target=\"z\"/><flowable:out source=\"y\" target=\"w\"/>"), "at most 1");
    }

    @Test
    void requiresCompleteAsyncForParallelMultiInstance() {
        String loop = "<multiInstanceLoopCharacteristics isSequential=\"false\" flowable:collection=\"${items}\""
                + " flowable:elementVariable=\"item\"/>";
        rejects("order", call("calledElement=\"payment\"", "").replace("</callActivity>", loop + "</callActivity>"),
                "completeAsync");

        assertThat(check("order", call("calledElement=\"payment\" flowable:completeAsync=\"true\"", "")
                .replace("</callActivity>", loop + "</callActivity>"))).hasSize(1);
        assertThat(check("order", call("calledElement=\"payment\"", "")
                .replace("</callActivity>", loop.replace("\"false\"", "\"true\"") + "</callActivity>"))).hasSize(1);
    }

    @Test
    void rejectsDirectAndIndirectCycles() {
        rejects("a", call("calledElement=\"a\"", ""), "a → a");

        published("b", "c");
        published("c", "a");
        rejects("a", call("calledElement=\"b\"", ""), "a → b → c → a");
    }

    @Test
    void ignoresReferencesOfVersionsThatAreNoLongerPublished() {
        ProcessDefinition oldC = latest("c");
        references.replaceForDefinition(TENANT, "c:old", List.of(
                new CallReference("c:old", "c", oldC.getVersion() - 1, "call", "a")));

        assertThat(check("a", call("calledElement=\"c\"", ""))).hasSize(1);
    }

    @Test
    void enforcesTheMaximumCallDepthAcrossCallersAndCallees() {
        published("b", "c");
        published("c", "d");
        // b → c → d is depth 3. Adding a → b makes a → b → c → d, depth 4.
        rejects("a", call("calledElement=\"b\"", ""), "maximum call depth 3");
        // d → payment would make b → c → d → payment, depth 4.
        rejects("d", call("calledElement=\"payment\"", ""), "maximum call depth 3");
        assertThat(check("a", call("calledElement=\"c\"", ""))).hasSize(1);
    }

    @Test
    void rejectsACalleeWhoseFirstTasksNeedAParticipantPicker() {
        when(coordinator.requirementsForStart(anyString(), anyString(), anyMap())).thenReturn(List.of(
                new ParticipantRequirementView("approve", "Duyệt", AssignmentType.ASSIGNEE, false, true)));

        rejects("order", call("calledElement=\"payment\"", ""), "approve");
    }

    @Test
    void acceptsACalleeWhenItsFirstStepsCannotBeEvaluatedWithoutVariables() {
        when(coordinator.requirementsForStart(anyString(), anyString(), anyMap()))
                .thenThrow(new BusinessException(
                        io.github.illuseahashmap.workflow.shared.exception.ErrorCode.BAD_REQUEST, "no variable"));

        assertThat(check("order", call("calledElement=\"payment\"", ""))).hasSize(1);
    }
}
