package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.dto.TaskParticipantAction;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.common.engine.impl.el.DefaultExpressionManager;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** Participant frontiers across embedded subprocesses, event subprocesses and call activities. */
@ExtendWith(MockitoExtension.class)
class FlowableUserTaskPathResolverSubProcessTest {

    @Mock
    private RepositoryService repositoryService;
    @Mock
    private ProcessEngineConfigurationImpl processEngineConfiguration;

    private FlowableUserTaskPathResolver resolver;

    @BeforeEach
    void setUp() {
        when(processEngineConfiguration.getExpressionManager())
                .thenReturn(new DefaultExpressionManager(Map.of()));
        lenient().when(repositoryService.getProcessDefinition(anyString())).thenReturn(null);
        resolver = new FlowableUserTaskPathResolver(repositoryService, processEngineConfiguration);
    }

    private void model(String processBody) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="p" isExecutable="true">%s</process>
                </definitions>""".formatted(processBody);
        BpmnModel model = new BpmnXMLConverter().convertToBpmnModel(
                () -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), false, false);
        when(repositoryService.getBpmnModel("def")).thenReturn(model);
    }

    private static List<String> ids(List<? extends FlowElement> elements) {
        return elements.stream().map(FlowElement::getId).toList();
    }

    private static Task task(String key) {
        Task task = Mockito.mock(Task.class);
        when(task.getProcessDefinitionId()).thenReturn("def");
        when(task.getTaskDefinitionKey()).thenReturn(key);
        return task;
    }

    @Test
    void entersAnEmbeddedSubProcess() {
        model("""
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
                <sequenceFlow id="f5" sourceRef="after" targetRef="e"/>""");

        assertThat(ids(resolver.firstUserTasks("def", Map.of()))).containsExactly("inner");
    }

    @Test
    void leavesTheSubProcessThroughItsEndEvent() {
        model("""
                <startEvent id="s"/>
                <userTask id="before"/>
                <subProcess id="sub">
                  <startEvent id="ss"/>
                  <serviceTask id="auto" flowable:expression="${true}"/>
                  <endEvent id="se"/>
                  <sequenceFlow id="f1" sourceRef="ss" targetRef="auto"/>
                  <sequenceFlow id="f2" sourceRef="auto" targetRef="se"/>
                </subProcess>
                <userTask id="after"/>
                <endEvent id="e"/>
                <sequenceFlow id="f0" sourceRef="s" targetRef="before"/>
                <sequenceFlow id="f3" sourceRef="before" targetRef="sub"/>
                <sequenceFlow id="f4" sourceRef="sub" targetRef="after"/>
                <sequenceFlow id="f5" sourceRef="after" targetRef="e"/>""");

        assertThat(ids(resolver.targetTasks(task("before"), TaskParticipantAction.APPROVE, null, Map.of())))
                .containsExactly("after");
    }

    @Test
    void completingTheLastTaskInsideContinuesAfterTheSubProcess() {
        model("""
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
                <sequenceFlow id="f5" sourceRef="after" targetRef="e"/>""");

        assertThat(ids(resolver.targetTasks(task("inner"), TaskParticipantAction.APPROVE, null, Map.of())))
                .containsExactly("after");
    }

    @Test
    void followsGatewaysAndNestingInsideSubProcesses() {
        model("""
                <startEvent id="s"/>
                <subProcess id="outer">
                  <startEvent id="os"/>
                  <exclusiveGateway id="g" default="toInner"/>
                  <subProcess id="inner">
                    <startEvent id="is"/>
                    <userTask id="deep"/>
                    <endEvent id="ie"/>
                    <sequenceFlow id="i1" sourceRef="is" targetRef="deep"/>
                    <sequenceFlow id="i2" sourceRef="deep" targetRef="ie"/>
                  </subProcess>
                  <userTask id="vip"/>
                  <endEvent id="oe"/>
                  <sequenceFlow id="o1" sourceRef="os" targetRef="g"/>
                  <sequenceFlow id="toVip" sourceRef="g" targetRef="vip">
                    <conditionExpression>${vip}</conditionExpression>
                  </sequenceFlow>
                  <sequenceFlow id="toInner" sourceRef="g" targetRef="inner"/>
                  <sequenceFlow id="o2" sourceRef="inner" targetRef="oe"/>
                  <sequenceFlow id="o3" sourceRef="vip" targetRef="oe"/>
                </subProcess>
                <endEvent id="e"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="outer"/>
                <sequenceFlow id="f2" sourceRef="outer" targetRef="e"/>""");

        assertThat(ids(resolver.firstUserTasks("def", Map.of("vip", false)))).containsExactly("deep");
        assertThat(ids(resolver.firstUserTasks("def", Map.of("vip", true)))).containsExactly("vip");
    }

    @Test
    void ignoresEventSubProcesses() {
        model("""
                <startEvent id="s"/>
                <userTask id="main"/>
                <endEvent id="e"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="main"/>
                <sequenceFlow id="f2" sourceRef="main" targetRef="e"/>
                <subProcess id="onTimeout" triggeredByEvent="true">
                  <startEvent id="ts"><timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition></startEvent>
                  <userTask id="handle"/>
                  <endEvent id="te"/>
                  <sequenceFlow id="t1" sourceRef="ts" targetRef="handle"/>
                  <sequenceFlow id="t2" sourceRef="handle" targetRef="te"/>
                </subProcess>""");

        assertThat(ids(resolver.firstUserTasks("def", Map.of()))).containsExactly("main");
        assertThat(ids(resolver.targetTasks(task("handle"), TaskParticipantAction.APPROVE, null, Map.of())))
                .isEmpty();
    }

    @Test
    void stopsAtACallActivity() {
        model("""
                <startEvent id="s"/>
                <callActivity id="call" calledElement="other"/>
                <userTask id="after"/>
                <endEvent id="e"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <sequenceFlow id="f2" sourceRef="call" targetRef="after"/>
                <sequenceFlow id="f3" sourceRef="after" targetRef="e"/>""");

        assertThat(resolver.firstUserTasks("def", Map.of())).isEmpty();
        assertThat(resolver.firstAgentTasks("def", Map.of())).isEmpty();
    }
}
