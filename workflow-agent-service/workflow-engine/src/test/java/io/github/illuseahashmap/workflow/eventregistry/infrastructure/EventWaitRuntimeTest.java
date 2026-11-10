package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.InboundEventResult;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveEventModelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveRestChannelRequest;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Slice 5 runtime checks of spec 2026-11-09-event-registry-d1-design, through the real webhook
 * receiver and the platform consumers. Processes are deployed directly: the deploy guard is not involved.
 * Two instances with different correlation values are covered by InboundEventReceiverImplTest.
 */
class EventWaitRuntimeTest {

    private static final String WAIT = """
            <flowable:eventType>paymentReceived</flowable:eventType>
            <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
            <flowable:eventOutParameter source="amount" target="paidAmount"/>""";

    private static EventRegistryTestEngine test;
    private static InboundEventReceiverImpl receiver;

    @BeforeAll
    static void start() {
        test = new EventRegistryTestEngine("event-wait-runtime");
        var properties = new EventRegistryProperties();
        properties.setEnabled(true);
        AtomicReference<TenantContext.TenantInfo> tenant = new AtomicReference<>(EventModelServiceImplTest.TENANT);
        AtomicReference<CurrentPrincipal> principal = new AtomicReference<>(EventModelServiceImplTest.DESIGNER);
        var repositories = new InMemoryEventRegistryRepositories();
        var audit = mock(WorkflowOperationAuditService.class);
        var events = new EventModelServiceImpl(repositories.drafts, repositories.releases, repositories.references,
                test.gateway, audit, tenant::get, principal::get, properties);
        var channels = new RestChannelServiceImpl(repositories.drafts, repositories.releases, test.gateway, audit,
                tenant::get, principal::get, properties);
        long revision = events.saveDraft("paymentReceived", new SaveEventModelRequest(0, "Payment", null,
                List.of(EventModelServiceImplTest.ORDER_ID, EventModelServiceImplTest.AMOUNT))).revision();
        events.publish("paymentReceived", revision);
        long channelRevision = channels.saveDraft("payWebhook", new SaveRestChannelRequest(0, "Pay", null, "FIXED",
                "paymentReceived", null, List.of("erp-client"), List.of())).revision();
        channels.publish("payWebhook", channelRevision);
        principal.set(InboundEventReceiverImplTest.ERP);
        receiver = new InboundEventReceiverImpl(properties, tenant::get, principal::get, test.channels,
                test.gateway, test.events.getEventRegistry(), new InboundEventReceiverImplTest.FakeIdempotency());
    }

    @AfterAll
    static void stop() {
        test.close();
    }

    private static void deploy(String key, String body) {
        test.engine.getRepositoryService().createDeployment().tenantId("t1").addString(key + ".bpmn20.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/>
                    %s
                  </process>
                </definitions>""".formatted(key, body)).deploy();
    }

    private static ProcessInstance start(String key, Map<String, Object> variables) {
        return test.engine.getRuntimeService().startProcessInstanceByKeyAndTenantId(key, variables, "t1");
    }

    private static boolean at(ProcessInstance instance, String activityId) {
        return test.engine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId(activityId).count() > 0;
    }

    private static InboundEventResult post(String body) {
        return receiver.receive("payWebhook", Map.of("Content-Type", "application/json"),
                body.getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());
    }

    private static String receiveThenUserTask(String extra) {
        return """
                <receiveTask id="wait"><extensionElements>%s</extensionElements></receiveTask>
                %s
                <userTask id="after"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>""".formatted(WAIT, extra);
    }

    @Test
    void textInTheBodyMatchesANumberVariableAndNullIsRefused() {
        deploy("runtimeNumber", receiveThenUserTask(""));
        ProcessInstance instance = start("runtimeNumber", Map.of("orderId", 123));

        assertThat(post("{\"orderId\":null,\"amount\":1}").code()).isEqualTo("INVALID_CORRELATION");
        assertThat(post("{\"orderId\":null,\"amount\":1}").httpStatus()).isEqualTo(422);
        assertThat(post("{\"orderId\":\"123\",\"amount\":1}").status()).isEqualTo("MATCHED");
        assertThat(at(instance, "after")).isTrue();
    }

    @Test
    void oneFailingInstanceDoesNotStopTheOthers() {
        // R-D2: no asyncLeave, so the gateway after the wait runs inside the trigger.
        deploy("runtimePartial", """
                <receiveTask id="wait"><extensionElements>%s</extensionElements></receiveTask>
                <exclusiveGateway id="gw"/>
                <userTask id="after"/>
                <userTask id="never"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="gw"/>
                <sequenceFlow id="f3" sourceRef="gw" targetRef="after">
                  <conditionExpression xsi:type="tFormalExpression">${fail == false}</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f4" sourceRef="gw" targetRef="never">
                  <conditionExpression xsi:type="tFormalExpression">${false}</conditionExpression>
                </sequenceFlow>""".formatted(WAIT));
        ProcessInstance a = start("runtimePartial", Map.of("orderId", "P-1", "fail", false));
        ProcessInstance b = start("runtimePartial", Map.of("orderId", "P-1", "fail", true));

        InboundEventResult result = post("{\"orderId\":\"P-1\",\"amount\":1}");

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(at(a, "after")).isTrue();
        assertThat(at(b, "wait")).isTrue();
        assertThat(result.failed()).singleElement().satisfies(delivery -> {
            assertThat(delivery.processInstanceId()).isEqualTo(b.getId());
            assertThat(delivery.errorCode()).isEqualTo("EVENT_PROCESSING_FAILED");
        });
    }

    @Test
    void timerFiredFirstLeavesNoWaitingInstance() {
        deploy("runtimeTimer", receiveThenUserTask("""
                <boundaryEvent id="timeout" attachedToRef="wait" cancelActivity="true">
                  <timerEventDefinition><timeDuration>PT1S</timeDuration></timerEventDefinition>
                </boundaryEvent>
                <userTask id="late"/>
                <sequenceFlow id="f3" sourceRef="timeout" targetRef="late"/>"""));
        ProcessInstance instance = start("runtimeTimer", Map.of("orderId", "T-9"));
        var management = test.engine.getManagementService();
        String timerId = management.createTimerJobQuery().processInstanceId(instance.getId()).singleResult().getId();
        management.executeJob(management.moveTimerToExecutableJob(timerId).getId());

        InboundEventResult result = post("{\"orderId\":\"T-9\",\"amount\":1}");

        assertThat(at(instance, "late")).isTrue();
        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(result.code()).isEqualTo("NO_WAITING_INSTANCE");
    }

    private static String boundary(boolean cancelActivity) {
        return """
                <userTask id="work"/>
                <boundaryEvent id="evt" attachedToRef="work" cancelActivity="%s">
                  <extensionElements>%s</extensionElements>
                </boundaryEvent>
                <userTask id="afterWork"/>
                <userTask id="afterEvent"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <sequenceFlow id="f2" sourceRef="work" targetRef="afterWork"/>
                <sequenceFlow id="f3" sourceRef="evt" targetRef="afterEvent"/>""".formatted(cancelActivity, WAIT);
    }

    @Test
    void interruptingBoundaryCancelsTheUserTask() {
        deploy("runtimeBoundaryCancel", boundary(true));
        ProcessInstance instance = start("runtimeBoundaryCancel", Map.of("orderId", "B-1"));

        assertThat(post("{\"orderId\":\"B-1\",\"amount\":1}").status()).isEqualTo("MATCHED");

        assertThat(at(instance, "work")).isFalse();
        assertThat(at(instance, "afterEvent")).isTrue();
    }

    @Test
    void nonInterruptingBoundaryKeepsTheUserTask() {
        deploy("runtimeBoundaryKeep", boundary(false));
        ProcessInstance instance = start("runtimeBoundaryKeep", Map.of("orderId", "B-2"));

        assertThat(post("{\"orderId\":\"B-2\",\"amount\":1}").status()).isEqualTo("MATCHED");

        assertThat(at(instance, "work")).isTrue();
        assertThat(at(instance, "afterEvent")).isTrue();
    }

    @Test
    void intermediateCatchIsMatchedAndMapsTheVariable() {
        deploy("runtimeCatch", """
                <intermediateCatchEvent id="wait"><extensionElements>%s</extensionElements></intermediateCatchEvent>
                <userTask id="after"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>""".formatted(WAIT));
        ProcessInstance instance = start("runtimeCatch", Map.of("orderId", "C-7"));

        InboundEventResult result = post("{\"orderId\":\"C-7\",\"amount\":42.5}");

        assertThat(result.status()).isEqualTo("MATCHED");
        assertThat(result.matched()).singleElement()
                .satisfies(delivery -> assertThat(delivery.activityId()).isEqualTo("wait"));
        assertThat(test.engine.getRuntimeService().getVariable(instance.getId(), "paidAmount")).isEqualTo(42.5);
    }
}
