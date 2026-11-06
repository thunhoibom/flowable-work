package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Stock Flowable 8.0.0 call activity behaviour the platform relies on. No platform code runs here.
 * After draft/publish, "latest" is always the published version, so stock resolution is correct.
 */
class CallActivityEngineFactsTest {

    private static final String TENANT = "tenant-call";
    private static ProcessEngine engine;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:call-activity-facts;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static String definitions(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                %s
                </definitions>""".formatted(body);
    }

    private static String childWithTask(String key, String marker) {
        return definitions("""
                <process id="%s" name="%s" isExecutable="true">
                  <startEvent id="s"/>
                  <userTask id="childTask"/>
                  <endEvent id="e"/>
                  <sequenceFlow id="f1" sourceRef="s" targetRef="childTask"/>
                  <sequenceFlow id="f2" sourceRef="childTask" targetRef="e"/>
                </process>""".formatted(key, marker));
    }

    private static String parentCalling(String key, String callee, String callAttributes, String extensions) {
        return definitions("""
                <process id="%s" isExecutable="true">
                  <startEvent id="s"/>
                  <callActivity id="call" calledElement="%s" %s>
                    <extensionElements>%s</extensionElements>
                  </callActivity>
                  <userTask id="after"/>
                  <endEvent id="e"/>
                  <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                  <sequenceFlow id="f2" sourceRef="call" targetRef="after"/>
                  <sequenceFlow id="f3" sourceRef="after" targetRef="e"/>
                </process>""".formatted(key, callee, callAttributes, extensions));
    }

    private static void deploy(String key, String xml) {
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", xml).deploy();
    }

    private static ProcessInstance start(String key, Map<String, Object> variables) {
        return engine.getRuntimeService().startProcessInstanceByKeyAndTenantId(key, variables, TENANT);
    }

    private static ProcessInstance child(ProcessInstance parent) {
        return engine.getRuntimeService().createProcessInstanceQuery()
                .superProcessInstanceId(parent.getId()).singleResult();
    }

    private static Task task(ProcessInstance instance) {
        return engine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).singleResult();
    }

    @Test
    void resolvesTheLatestVersionInTheTenant() {
        deploy("c1", childWithTask("c1", "v1"));
        deploy("c1", childWithTask("c1", "v2"));
        deploy("p1", parentCalling("p1", "c1", "", ""));

        ProcessInstance child = child(start("p1", Map.of()));

        var definition = engine.getRepositoryService().getProcessDefinition(child.getProcessDefinitionId());
        assertThat(definition.getVersion()).isEqualTo(2);
        assertThat(child.getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void suspendedCalleeFailsAtTheCallActivity() {
        deploy("c2", childWithTask("c2", "v1"));
        deploy("p2", parentCalling("p2", "c2", "", ""));
        var callee = engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionKey("c2").processDefinitionTenantId(TENANT).latestVersion().singleResult();
        engine.getRepositoryService().suspendProcessDefinitionById(callee.getId(), false, null);

        assertThatThrownBy(() -> start("p2", Map.of()))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("suspended");
    }

    @Test
    void inAndOutParametersMapVariablesBothWays() {
        deploy("c3", childWithTask("c3", "v1"));
        deploy("p3", parentCalling("p3", "c3", "flowable:useLocalScopeForOutParameters=\"true\"", """
                <flowable:in source="orderId" target="order"/>
                <flowable:in sourceExpression="${amount * 2}" target="doubled"/>
                <flowable:out source="status" target="paymentStatus"/>"""));
        ProcessInstance parent = start("p3", Map.of("orderId", "O-1", "amount", 21, "secret", "s"));
        ProcessInstance child = child(parent);

        assertThat(engine.getRuntimeService().getVariables(child.getId()))
                .containsEntry("order", "O-1").containsEntry("doubled", 42L).doesNotContainKey("secret");

        engine.getRuntimeService().setVariable(child.getId(), "status", "PAID");
        engine.getTaskService().complete(task(child).getId());

        assertThat(task(parent).getTaskDefinitionKey()).isEqualTo("after");
        // useLocalScopeForOutParameters: written on the call activity execution, which is gone now.
        assertThat(engine.getRuntimeService().getVariables(parent.getId())).doesNotContainKey("paymentStatus");
    }

    @Test
    void unhandledBpmnErrorInTheChildIsCaughtByABoundaryOnTheCallActivity() {
        deploy("c4", definitions("""
                <error id="err" errorCode="PAYMENT_FAILED"/>
                <process id="c4" isExecutable="true">
                  <startEvent id="s"/>
                  <endEvent id="fail"><errorEventDefinition errorRef="err"/></endEvent>
                  <sequenceFlow id="f1" sourceRef="s" targetRef="fail"/>
                </process>"""));
        deploy("p4", definitions("""
                <error id="err" errorCode="PAYMENT_FAILED"/>
                <process id="p4" isExecutable="true">
                  <startEvent id="s"/>
                  <callActivity id="call" calledElement="c4"/>
                  <boundaryEvent id="onFail" attachedToRef="call">
                    <errorEventDefinition errorRef="err"/>
                  </boundaryEvent>
                  <userTask id="handle"/>
                  <endEvent id="e"/>
                  <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                  <sequenceFlow id="f2" sourceRef="call" targetRef="e"/>
                  <sequenceFlow id="f3" sourceRef="onFail" targetRef="handle"/>
                </process>"""));

        ProcessInstance parent = start("p4", Map.of());

        assertThat(task(parent).getTaskDefinitionKey()).isEqualTo("handle");
    }

    @Test
    void deletingTheParentDeletesTheChild() {
        deploy("c5", childWithTask("c5", "v1"));
        deploy("p5", parentCalling("p5", "c5", "", ""));
        ProcessInstance parent = start("p5", Map.of());
        ProcessInstance child = child(parent);

        engine.getRuntimeService().deleteProcessInstance(parent.getId(), "test");

        assertThat(engine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(child.getId()).count()).isZero();
    }

    @Test
    void parallelMultiInstanceWithCompleteAsyncFinishesAllChildren() {
        deploy("c6", childWithTask("c6", "v1"));
        deploy("p6", definitions("""
                <process id="p6" isExecutable="true">
                  <startEvent id="s"/>
                  <callActivity id="call" calledElement="c6" flowable:completeAsync="true">
                    <multiInstanceLoopCharacteristics isSequential="false"
                        flowable:collection="${items}" flowable:elementVariable="item"/>
                  </callActivity>
                  <userTask id="after"/>
                  <endEvent id="e"/>
                  <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                  <sequenceFlow id="f2" sourceRef="call" targetRef="after"/>
                  <sequenceFlow id="f3" sourceRef="after" targetRef="e"/>
                </process>"""));
        ProcessInstance parent = start("p6", Map.of("items", IntStream.range(0, 10).boxed().toList()));
        List<ProcessInstance> children = engine.getRuntimeService().createProcessInstanceQuery()
                .superProcessInstanceId(parent.getId()).list();
        assertThat(children).hasSize(10);

        children.forEach(child -> engine.getTaskService().complete(task(child).getId()));
        for (Job job : engine.getManagementService().createJobQuery().processInstanceId(parent.getId()).list()) {
            engine.getManagementService().executeJob(job.getId());
        }

        assertThat(task(parent).getTaskDefinitionKey()).isEqualTo("after");
    }
}
