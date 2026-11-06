package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins down Flowable 8.0.0 subprocess behaviour that the platform relies on.
 * No platform code is involved: if Flowable changes, these tests fail first.
 */
class SubProcessEngineTest {

    private static ProcessEngine engine;

    /** Throws the BPMN error named by the {@code errorCode} variable. */
    public static class ThrowingDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            throw new BpmnError((String) execution.getVariable("errorCode"));
        }
    }

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:subprocess-facts;DB_CLOSE_DELAY=-1");
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
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                             targetNamespace="t">
                %s
                </definitions>""".formatted(body);
    }

    private static void deploy(String name, String xml) {
        engine.getRepositoryService().createDeployment().addString(name + ".bpmn20.xml", xml).deploy();
    }

    private static List<Task> tasks(ProcessInstance instance) {
        return engine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).list();
    }

    @Test
    void variablesSetLocallyStayInsideTheSubProcess() {
        deploy("local", definitions("""
                <process id="local" isExecutable="true">
                  <startEvent id="s"/>
                  <subProcess id="sub">
                    <startEvent id="ss"/>
                    <userTask id="inner"/>
                    <endEvent id="se"/>
                    <sequenceFlow id="f1" sourceRef="ss" targetRef="inner"/>
                    <sequenceFlow id="f2" sourceRef="inner" targetRef="se"/>
                  </subProcess>
                  <userTask id="after"/>
                  <endEvent id="e"/>
                  <sequenceFlow id="f3" sourceRef="s" targetRef="sub"/>
                  <sequenceFlow id="f4" sourceRef="sub" targetRef="after"/>
                  <sequenceFlow id="f5" sourceRef="after" targetRef="e"/>
                </process>"""));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("local");
        Task inner = tasks(instance).getFirst();
        assertThat(inner.getTaskDefinitionKey()).isEqualTo("inner");

        engine.getRuntimeService().setVariableLocal(inner.getExecutionId(), "scoped", "x");
        engine.getTaskService().complete(inner.getId());

        Task after = tasks(instance).getFirst();
        assertThat(after.getTaskDefinitionKey()).isEqualTo("after");
        assertThat(engine.getRuntimeService().getVariables(instance.getId())).doesNotContainKey("scoped");
    }

    @Test
    void parallelMultiInstanceSubProcessStopsAtTheCompletionCondition() {
        deploy("multi", definitions("""
                <process id="multi" isExecutable="true">
                  <startEvent id="s"/>
                  <subProcess id="review">
                    <multiInstanceLoopCharacteristics isSequential="false"
                        flowable:collection="${reviewers}" flowable:elementVariable="reviewer">
                      <completionCondition>${nrOfCompletedInstances >= 2}</completionCondition>
                    </multiInstanceLoopCharacteristics>
                    <startEvent id="rs"/>
                    <userTask id="check" flowable:assignee="${reviewer}"/>
                    <endEvent id="re"/>
                    <sequenceFlow id="f1" sourceRef="rs" targetRef="check"/>
                    <sequenceFlow id="f2" sourceRef="check" targetRef="re"/>
                  </subProcess>
                  <userTask id="after"/>
                  <endEvent id="e"/>
                  <sequenceFlow id="f3" sourceRef="s" targetRef="review"/>
                  <sequenceFlow id="f4" sourceRef="review" targetRef="after"/>
                  <sequenceFlow id="f5" sourceRef="after" targetRef="e"/>
                </process>"""));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey(
                "multi", Map.of("reviewers", List.of("a", "b", "c")));
        List<Task> checks = tasks(instance);
        assertThat(checks).extracting(Task::getAssignee).containsExactlyInAnyOrder("a", "b", "c");

        engine.getTaskService().complete(checks.get(0).getId());
        engine.getTaskService().complete(checks.get(1).getId());

        assertThat(tasks(instance)).extracting(Task::getTaskDefinitionKey).containsExactly("after");
    }

    @Test
    void interruptingBoundaryTimerCancelsEveryInnerExecution() {
        deploy("timer", definitions("""
                <process id="timer" isExecutable="true">
                  <startEvent id="s"/>
                  <subProcess id="work">
                    <startEvent id="ws"/>
                    <parallelGateway id="fork"/>
                    <userTask id="a"/>
                    <userTask id="b"/>
                    <parallelGateway id="join"/>
                    <endEvent id="we"/>
                    <sequenceFlow id="f1" sourceRef="ws" targetRef="fork"/>
                    <sequenceFlow id="f2" sourceRef="fork" targetRef="a"/>
                    <sequenceFlow id="f3" sourceRef="fork" targetRef="b"/>
                    <sequenceFlow id="f4" sourceRef="a" targetRef="join"/>
                    <sequenceFlow id="f5" sourceRef="b" targetRef="join"/>
                    <sequenceFlow id="f6" sourceRef="join" targetRef="we"/>
                  </subProcess>
                  <boundaryEvent id="late" attachedToRef="work" cancelActivity="true">
                    <timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition>
                  </boundaryEvent>
                  <userTask id="escalate"/>
                  <endEvent id="e"/>
                  <sequenceFlow id="f7" sourceRef="s" targetRef="work"/>
                  <sequenceFlow id="f8" sourceRef="work" targetRef="e"/>
                  <sequenceFlow id="f9" sourceRef="late" targetRef="escalate"/>
                </process>"""));
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("timer");
        assertThat(tasks(instance)).hasSize(2);

        Job timer = engine.getManagementService().createTimerJobQuery()
                .processInstanceId(instance.getId()).singleResult();
        engine.getManagementService().moveTimerToExecutableJob(timer.getId());
        engine.getManagementService().executeJob(timer.getId());

        assertThat(tasks(instance)).extracting(Task::getTaskDefinitionKey).containsExactly("escalate");
    }

    @Test
    void errorEventSubProcessCatchesABpmnErrorFromInside() {
        deploy("errors", definitions("""
                <error id="paymentFailed" errorCode="PAYMENT_FAILED"/>
                <process id="errors" isExecutable="true">
                  <startEvent id="s"/>
                  <serviceTask id="pay" flowable:class="%s"/>
                  <endEvent id="e"/>
                  <sequenceFlow id="f1" sourceRef="s" targetRef="pay"/>
                  <sequenceFlow id="f2" sourceRef="pay" targetRef="e"/>
                  <subProcess id="onError" triggeredByEvent="true">
                    <startEvent id="es"><errorEventDefinition errorRef="paymentFailed"/></startEvent>
                    <userTask id="handle"/>
                    <endEvent id="ee"/>
                    <sequenceFlow id="f3" sourceRef="es" targetRef="handle"/>
                    <sequenceFlow id="f4" sourceRef="handle" targetRef="ee"/>
                  </subProcess>
                </process>""".formatted(ThrowingDelegate.class.getName())));

        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey(
                "errors", Map.of("errorCode", "PAYMENT_FAILED"));

        assertThat(tasks(instance)).extracting(Task::getTaskDefinitionKey).containsExactly("handle");
    }

    @Test
    void subProcessWithoutNoneStartEventFailsWhenEntered() {
        deploy("nostart", definitions("""
                <process id="nostart" isExecutable="true">
                  <startEvent id="s"/>
                  <subProcess id="sub">
                    <userTask id="inner"/>
                  </subProcess>
                  <endEvent id="e"/>
                  <sequenceFlow id="f1" sourceRef="s" targetRef="sub"/>
                  <sequenceFlow id="f2" sourceRef="sub" targetRef="e"/>
                </process>"""));

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("nostart"))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining("No initial activity found");
    }

    @Test
    void collapsedSubProcessWithItsOwnDiagramPlaneDeploys() {
        deploy("planes", definitions("""
                <process id="planes" isExecutable="true">
                  <startEvent id="s"/>
                  <subProcess id="sub">
                    <startEvent id="ss"/>
                    <endEvent id="se"/>
                    <sequenceFlow id="f1" sourceRef="ss" targetRef="se"/>
                  </subProcess>
                  <endEvent id="e"/>
                  <sequenceFlow id="f2" sourceRef="s" targetRef="sub"/>
                  <sequenceFlow id="f3" sourceRef="sub" targetRef="e"/>
                </process>
                <bpmndi:BPMNDiagram id="d1">
                  <bpmndi:BPMNPlane id="p1" bpmnElement="planes">
                    <bpmndi:BPMNShape id="sub_di" bpmnElement="sub" isExpanded="false">
                      <dc:Bounds x="100" y="100" width="100" height="80"/>
                    </bpmndi:BPMNShape>
                  </bpmndi:BPMNPlane>
                </bpmndi:BPMNDiagram>
                <bpmndi:BPMNDiagram id="d2">
                  <bpmndi:BPMNPlane id="p2" bpmnElement="sub">
                    <bpmndi:BPMNShape id="ss_di" bpmnElement="ss">
                      <dc:Bounds x="10" y="10" width="36" height="36"/>
                    </bpmndi:BPMNShape>
                  </bpmndi:BPMNPlane>
                </bpmndi:BPMNDiagram>"""));

        var definition = engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionKey("planes").singleResult();
        var model = engine.getRepositoryService().getBpmnModel(definition.getId());
        assertThat(model.getGraphicInfo("sub")).isNotNull();
        assertThat(model.getGraphicInfo("ss")).isNotNull();
        assertThat(engine.getRuntimeService().startProcessInstanceByKey("planes").isEnded()).isTrue();
    }
}
