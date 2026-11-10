package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventFieldType;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventRegistryDeploymentGuardTest {

    static final TenantContext.TenantInfo TENANT = new TenantContext.TenantInfo("t1", "t1", "Tenant 1");
    static final List<EventField> PAYMENT = List.of(
            new EventField("orderId", EventFieldType.STRING, true, false, false),
            new EventField("region", EventFieldType.STRING, true, false, false),
            new EventField("amount", EventFieldType.DOUBLE, false, false, false),
            new EventField("source", EventFieldType.STRING, false, true, false));

    private final EventRegistryProperties properties = new EventRegistryProperties();
    private final EventRegistryGateway gateway = mock(EventRegistryGateway.class);
    private final EventRegistryDeploymentGuard guard = new EventRegistryDeploymentGuard(properties, gateway);

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

    /** A receive task, intermediate catch or boundary waiting for {@code key} with these extensions. */
    static String wait(String element, String id, String key, String extensions) {
        String attached = "boundaryEvent".equals(element) ? " attachedToRef=\"work\"" : "";
        return """
                <%s id="%s"%s><extensionElements>
                  <flowable:eventType>%s</flowable:eventType>
                  %s
                </extensionElements></%s>""".formatted(element, id, attached, key, extensions, element);
    }

    static final String ORDER = "<flowable:eventCorrelationParameter name=\"orderId\" value=\"${orderId}\"/>";

    @BeforeEach
    void library() {
        properties.setEnabled(true);
        when(gateway.publishedEventFields(anyString(), anyString())).thenReturn(Optional.empty());
        when(gateway.publishedEventFields("t1", "paymentReceived")).thenReturn(Optional.of(PAYMENT));
    }

    private List<EventWaitBinding> check(String body) {
        return guard.check(TENANT, bpmn(body));
    }

    private void rejects(String body, String... messages) {
        assertThatThrownBy(() -> check(body))
                .isInstanceOf(BusinessException.class)
                .hasMessageContainingAll(messages);
    }

    @Test
    void flagOffRejectsEventWaits() {
        properties.setEnabled(false);

        rejects(wait("receiveTask", "wait", "paymentReceived", ORDER), "Bước wait",
                "tính năng sự kiện chưa được bật");
    }

    @Test
    void acceptsTheThreeWaitKindsAndReturnsThem() {
        List<EventWaitBinding> bindings = check("<userTask id=\"work\"/>"
                + wait("receiveTask", "wait", "paymentReceived",
                        ORDER + "<flowable:eventOutParameter source=\"amount\" target=\"paidAmount\"/>"
                                + "<flowable:eventOutParameter source=\"source\" target=\"src\" transient=\"true\"/>")
                + wait("intermediateCatchEvent", "catch", "paymentReceived", ORDER)
                + wait("boundaryEvent", "b", "paymentReceived", ORDER));

        assertThat(bindings).extracting(EventWaitBinding::activityId).containsExactly("wait", "catch", "b");
    }

    @Test
    void manualWaitsReturnNothingWhateverTheFlag() {
        String manual = "<receiveTask id=\"wait\" flowable:asyncLeave=\"true\"/>"
                + "<receiveTask id=\"agent\"><extensionElements><workflow:agentTask agentVersionId=\"1\"/>"
                + "</extensionElements></receiveTask>";
        assertThat(check(manual)).isEmpty();
        properties.setEnabled(false);
        assertThat(check(manual)).isEmpty();
    }

    @Test
    void rejectsAnEventDefinitionTogetherWithAnEventType() {
        rejects("<userTask id=\"work\"/>" + """
                <boundaryEvent id="b" attachedToRef="work">
                  <extensionElements><flowable:eventType>paymentReceived</flowable:eventType>%s</extensionElements>
                  <timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition>
                </boundaryEvent>""".formatted(ORDER),
                "Bước b không được vừa có định nghĩa sự kiện vừa chờ sự kiện Event Registry");
    }

    @Test
    void rejectsKeysThatAreNotFixedValidCodes() {
        rejects(wait("receiveTask", "w1", "", ORDER)
                        + wait("receiveTask", "w2", "${key}", ORDER)
                        + wait("receiveTask", "w3", "9bad", ORDER),
                "Bước w1: mã sự kiện phải là mã cố định hợp lệ",
                "Bước w2: mã sự kiện phải là mã cố định hợp lệ",
                "Bước w3: mã sự kiện phải là mã cố định hợp lệ");
    }

    @Test
    void rejectsAnEventThatIsNotPublished() {
        rejects(wait("receiveTask", "wait", "unknownEvent", ORDER), "Bước wait: sự kiện unknownEvent chưa được phát hành");
    }

    @Test
    void demandsACorrelation() {
        rejects(wait("intermediateCatchEvent", "catch", "paymentReceived", ""),
                "Bước catch phải có ít nhất một trường tương quan");
    }

    @Test
    void reportsEveryCorrelationProblemTogether() {
        rejects(wait("receiveTask", "wait", "paymentReceived", ORDER + ORDER
                        + "<flowable:eventCorrelationParameter name=\"amount\" value=\"${amount}\"/>"
                        + "<flowable:eventCorrelationParameter name=\"region\" value=\" \"/>"),
                "Bước wait: trường tương quan orderId bị lặp",
                "Bước wait: sự kiện paymentReceived không có trường tương quan amount",
                "Bước wait: trường tương quan region chưa có giá trị", "; ");
    }

    @Test
    void reportsEveryOutputProblemTogether() {
        rejects(wait("receiveTask", "wait", "paymentReceived", ORDER
                        + "<flowable:eventOutParameter source=\"missing\" target=\"x\"/>"
                        + "<flowable:eventOutParameter source=\"amount\" target=\"1bad\"/>"
                        + "<flowable:eventOutParameter source=\"amount\" target=\"tenantId\"/>"
                        + "<flowable:eventOutParameter source=\"amount\" target=\"paid\"/>"
                        + "<flowable:eventOutParameter source=\"source\" target=\"paid\"/>"),
                "Bước wait: sự kiện paymentReceived không có trường missing",
                "Bước wait: tên biến 1bad không hợp lệ",
                "Bước wait: biến tenantId do hệ thống quản lý",
                "Bước wait: biến paid được gán hai lần");
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
    void rejectsEventStartTriggerAndSendEventAsLaterPhases() {
        rejects("""
                <startEvent id="es"><extensionElements>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></startEvent>""", "Bước es dùng tính năng sự kiện của đợt sau");
        rejects("<serviceTask id=\"send\" flowable:type=\"send-event\"/>", "Bước send", "đợt sau");
        rejects("""
                <serviceTask id="sr" flowable:triggerable="true">
                  <extensionElements><flowable:triggerEventType>reply</flowable:triggerEventType></extensionElements>
                </serviceTask>""", "Bước sr", "đợt sau");
        rejects("""
                <receiveTask id="trig"><extensionElements>
                  <activiti:triggerEventCorrelationParameter name="orderId" value="${orderId}"/>
                </extensionElements></receiveTask>""", "Bước trig", "đợt sau");
    }

    @Test
    void rejectsReceiveTaskAfterEventBasedGateway() {
        rejects("""
                <eventBasedGateway id="g"/>
                <receiveTask id="wait"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
                <sequenceFlow id="f2" sourceRef="g" targetRef="wait"/>""", "wait", "cổng rẽ theo sự kiện");
    }
}
