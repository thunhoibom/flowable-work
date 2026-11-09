package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class EventRegistryDeploymentGuardTest {

    private final EventRegistryDeploymentGuard guard = new EventRegistryDeploymentGuard();

    static String bpmn(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:activiti="http://activiti.org/bpmn"
                             xmlns:workflow="http://workflow-agent.local/bpmn"
                             targetNamespace="t">
                  <process id="p" isExecutable="true">
                    <startEvent id="s"/>
                    %s
                  </process>
                </definitions>""".formatted(body);
    }

    private void rejects(String body, String message) {
        assertThatThrownBy(() -> guard.check(bpmn(body)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(message);
    }

    @Test
    void acceptsManualWaitAgentTaskAndAsyncLeave() {
        assertThatCode(() -> guard.check(bpmn("""
                <receiveTask id="wait" flowable:asyncLeave="true"/>
                <receiveTask id="agent"><extensionElements>
                  <workflow:agentTask agentVersionId="1"/>
                </extensionElements></receiveTask>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>""")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsEventWaitsUntilSliceFive() {
        rejects("""
                <receiveTask id="wait"><extensionElements>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></receiveTask>""", "wait");
        rejects("""
                <intermediateCatchEvent id="catch"><extensionElements>
                  <activiti:eventType>paymentReceived</activiti:eventType>
                </extensionElements></intermediateCatchEvent>""", "catch");
        rejects("""
                <boundaryEvent id="b" attachedToRef="wait"><extensionElements>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></boundaryEvent>""", "b");
    }

    @Test
    void rejectsAgentTaskThatAlsoWaitsForAnEvent() {
        rejects("""
                <receiveTask id="both"><extensionElements>
                  <workflow:agentTask agentVersionId="1"/>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></receiveTask>""", "vừa là tác vụ Agent");
    }

    @Test
    void rejectsEventStartAndSendEvent() {
        rejects("""
                <startEvent id="es"><extensionElements>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></startEvent>""", "es");
        rejects("<serviceTask id=\"send\" flowable:type=\"send-event\"/>", "send");
        rejects("""
                <serviceTask id="sr" flowable:type="send-event" flowable:triggerable="true">
                  <extensionElements><flowable:triggerEventType>reply</flowable:triggerEventType></extensionElements>
                </serviceTask>""", "sr");
    }

    @Test
    void rejectsReceiveTaskAfterEventBasedGateway() {
        rejects("""
                <eventBasedGateway id="g"/>
                <receiveTask id="wait"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
                <sequenceFlow id="f2" sourceRef="g" targetRef="wait"/>""", "wait");
    }
}
