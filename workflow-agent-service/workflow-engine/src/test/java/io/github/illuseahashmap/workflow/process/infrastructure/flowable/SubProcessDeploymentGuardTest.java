package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.config.SubProcessProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class SubProcessDeploymentGuardTest {

    private final SubProcessDeploymentGuard guard = new SubProcessDeploymentGuard(new SubProcessProperties());

    static String bpmn(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <error id="err" errorCode="E1"/>
                  <process id="p" isExecutable="true">
                    <startEvent id="s"/>
                    %s
                  </process>
                </definitions>""".formatted(body);
    }

    static String sub(String id, String inner) {
        return """
                <subProcess id="%s">
                  <startEvent id="%s_s"/>
                  %s
                  <endEvent id="%s_e"/>
                </subProcess>""".formatted(id, id, inner, id);
    }

    private void rejects(String body, String message) {
        assertThatThrownBy(() -> guard.check(bpmn(body)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(message);
    }

    @Test
    void acceptsAValidEmbeddedSubProcessWithTimerAndErrorBoundaries() {
        String body = sub("review", "<userTask id=\"t\"/>") + """
                <boundaryEvent id="late" attachedToRef="review">
                  <timerEventDefinition><timeDuration>P3D</timeDuration></timerEventDefinition>
                </boundaryEvent>
                <boundaryEvent id="failed" attachedToRef="review">
                  <errorEventDefinition errorRef="err"/>
                </boundaryEvent>""";

        assertThatCode(() -> guard.check(bpmn(body))).doesNotThrowAnyException();
    }

    @Test
    void acceptsDocumentsWithoutSubProcesses() {
        assertThatCode(() -> guard.check(bpmn("<endEvent id=\"e\"/>"))).doesNotThrowAnyException();
    }

    @Test
    void acceptsErrorAndTimerEventSubProcesses() {
        String body = """
                <subProcess id="onError" triggeredByEvent="true">
                  <startEvent id="es"><errorEventDefinition errorRef="err"/></startEvent>
                  <endEvent id="ee"/>
                </subProcess>
                <subProcess id="onTimer" triggeredByEvent="true">
                  <startEvent id="ts" isInterrupting="false">
                    <timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition>
                  </startEvent>
                  <endEvent id="te"/>
                </subProcess>""";

        assertThatCode(() -> guard.check(bpmn(body))).doesNotThrowAnyException();
    }

    @Test
    void rejectsASubProcessWithoutANoneStartEvent() {
        rejects("""
                <subProcess id="sub">
                  <startEvent id="ss"><timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition></startEvent>
                  <endEvent id="se"/>
                </subProcess>""", "exactly one start event without a trigger");
        rejects("<subProcess id=\"sub\"><endEvent id=\"se\"/></subProcess>", "exactly one start event");
    }

    @Test
    void rejectsASubProcessWithTwoStartEvents() {
        rejects("""
                <subProcess id="sub">
                  <startEvent id="a"/><startEvent id="b"/><endEvent id="se"/>
                </subProcess>""", "exactly one start event");
    }

    @Test
    void rejectsASubProcessWithoutAnEndEvent() {
        rejects("<subProcess id=\"sub\"><startEvent id=\"ss\"/></subProcess>", "end event");
    }

    @Test
    void rejectsNestingDeeperThanTheLimit() {
        String depth3 = sub("l1", sub("l2", sub("l3", "")));
        assertThatCode(() -> guard.check(bpmn(depth3))).doesNotThrowAnyException();

        rejects(sub("l1", sub("l2", sub("l3", sub("l4", "")))), "nested deeper than 3");
    }

    @Test
    void rejectsAnEventSubProcessWithSequenceFlows() {
        rejects("""
                <subProcess id="onError" triggeredByEvent="true">
                  <startEvent id="es"><errorEventDefinition errorRef="err"/></startEvent>
                  <endEvent id="ee"/>
                </subProcess>
                <sequenceFlow id="bad" sourceRef="s" targetRef="onError"/>""", "sequence flow");
    }

    @Test
    void rejectsAnEventSubProcessStartOtherThanErrorOrTimer() {
        rejects("""
                <subProcess id="onMessage" triggeredByEvent="true">
                  <startEvent id="ms"><messageEventDefinition messageRef="m"/></startEvent>
                  <endEvent id="me"/>
                </subProcess>""", "error or timer");
        rejects("""
                <subProcess id="onNothing" triggeredByEvent="true">
                  <startEvent id="ns"/>
                  <endEvent id="ne"/>
                </subProcess>""", "error or timer");
    }

    @Test
    void rejectsABoundaryOnASubProcessOtherThanTimerOrError() {
        rejects(sub("sub", "") + """
                <boundaryEvent id="msg" attachedToRef="sub">
                  <messageEventDefinition messageRef="m"/>
                </boundaryEvent>""", "timer, error or Event Registry");
    }

    @Test
    void acceptsAnEventRegistryBoundaryOnASubProcess() {
        String body = sub("sub", "") + """
                <boundaryEvent id="cancelled" attachedToRef="sub" xmlns:flowable="http://flowable.org/bpmn">
                  <extensionElements><flowable:eventType>orderCancelled</flowable:eventType></extensionElements>
                </boundaryEvent>""";

        assertThatCode(() -> guard.check(bpmn(body))).doesNotThrowAnyException();
    }

    @Test
    void rejectsAMessageBoundaryOnASubProcessEvenWithAnEventType() {
        rejects(sub("sub", "") + """
                <boundaryEvent id="msg" attachedToRef="sub" xmlns:flowable="http://flowable.org/bpmn">
                  <extensionElements><flowable:eventType>orderCancelled</flowable:eventType></extensionElements>
                  <messageEventDefinition messageRef="m"/>
                </boundaryEvent>""", "timer, error or Event Registry");
    }

    @Test
    void rejectsAdHocAndTransactionSubProcesses() {
        rejects("<adHocSubProcess id=\"adhoc\"><userTask id=\"t\"/></adHocSubProcess>", "not supported");
        rejects("<transaction id=\"tx\"><startEvent id=\"ts\"/><endEvent id=\"te\"/></transaction>",
                "not supported");
    }
}
