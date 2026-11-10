package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.InboundEventResult;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveEventModelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveRestChannelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.port.InboundIdempotency;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InboundEventReceiverImplTest {

    static final CurrentPrincipal ERP = new CurrentPrincipal("SERVICE", "erp-client", null, "ERP", "t1",
            Set.of(), Set.of());
    static final TenantContext.TenantInfo OTHER = new TenantContext.TenantInfo("t2", "t2", "Tenant 2");

    private static EventRegistryTestEngine test;
    private static final AtomicReference<TenantContext.TenantInfo> TENANT = new AtomicReference<>();
    private static final AtomicReference<CurrentPrincipal> PRINCIPAL = new AtomicReference<>();
    private static final EventRegistryProperties PROPERTIES = new EventRegistryProperties();
    private static final FakeIdempotency IDEMPOTENCY = new FakeIdempotency();
    private static InboundEventReceiverImpl receiver;

    @BeforeAll
    static void start() {
        test = new EventRegistryTestEngine("inbound-event-receiver");
        PROPERTIES.setEnabled(true);
        TENANT.set(EventModelServiceImplTest.TENANT);
        PRINCIPAL.set(EventModelServiceImplTest.DESIGNER);
        var repositories = new InMemoryEventRegistryRepositories();
        var audit = mock(WorkflowOperationAuditService.class);
        var events = new EventModelServiceImpl(repositories.drafts, repositories.releases, repositories.references,
                test.gateway, audit, TENANT::get, PRINCIPAL::get, PROPERTIES);
        var channels = new RestChannelServiceImpl(repositories.drafts, repositories.releases, test.gateway, audit,
                TENANT::get, PRINCIPAL::get, PROPERTIES);

        publishEvent(events, "paymentReceived", EventModelServiceImplTest.ORDER_ID, EventModelServiceImplTest.AMOUNT);
        publishChannel(channels, "payWebhook", "paymentReceived");
        test.deployWaitingProcess("t1", "payProcess", "paymentReceived");

        publishEvent(events, "headerProbe", EventModelServiceImplTest.ORDER_ID,
                new EventFieldView("authorization", "string", false, true, false),
                new EventFieldView("source", "string", false, true, false));
        publishChannel(channels, "headerHook", "headerProbe");
        test.engine.getRepositoryService().createDeployment().tenantId("t1")
                .addString("header.bpmn20.xml", EventRegistryTestEngine.waitingProcess("headerProcess", "headerProbe")
                        .replace("<flowable:eventOutParameter source=\"amount\" target=\"paidAmount\"/>",
                                "<flowable:eventOutParameter source=\"authorization\" target=\"auth\"/>"
                                        + "<flowable:eventOutParameter source=\"source\" target=\"src\"/>"))
                .deploy();

        publishEvent(events, "twoKeys", EventModelServiceImplTest.ORDER_ID,
                new EventFieldView("region", "string", true, false, false));
        publishChannel(channels, "twoKeysHook", "twoKeys");

        receiver = new InboundEventReceiverImpl(PROPERTIES, TENANT::get, PRINCIPAL::get, test.channels,
                test.gateway, test.events.getEventRegistry(), IDEMPOTENCY);
    }

    @AfterAll
    static void stop() {
        test.close();
    }

    @BeforeEach
    void caller() {
        TENANT.set(EventModelServiceImplTest.TENANT);
        PRINCIPAL.set(ERP);
    }

    private static void publishEvent(EventModelServiceImpl events, String key, EventFieldView... fields) {
        long revision = events.saveDraft(key, new SaveEventModelRequest(0, key, null, List.of(fields))).revision();
        events.publish(key, revision);
    }

    private static void publishChannel(RestChannelServiceImpl channels, String key, String eventKey) {
        long revision = channels.saveDraft(key, new SaveRestChannelRequest(0, key, null, "FIXED", eventKey,
                null, List.of("erp-client"), List.of())).revision();
        channels.publish(key, revision);
    }

    private static ProcessInstance start(String processKey, String orderId) {
        return test.engine.getRuntimeService().startProcessInstanceByKeyAndTenantId(processKey,
                Map.of("orderId", orderId), "t1");
    }

    private static boolean waiting(ProcessInstance instance) {
        return test.engine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("wait").count() > 0;
    }

    private static InboundEventResult post(String channel, String body, String key) {
        return receiver.receive(channel, Map.of("Content-Type", "application/json"),
                body.getBytes(StandardCharsets.UTF_8), key);
    }

    private static InboundEventResult post(String channel, String body) {
        return post(channel, body, UUID.randomUUID().toString());
    }

    @Test
    void matchingEventResumesOnlyItsInstanceAndAReplayIsADuplicate() {
        ProcessInstance first = start("payProcess", "A-1");
        ProcessInstance second = start("payProcess", "A-2");
        String key = UUID.randomUUID().toString();

        InboundEventResult result = post("payWebhook", "{\"orderId\":\"A-1\",\"amount\":12.5}", key);

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.status()).isEqualTo("MATCHED");
        assertThat(result.matched()).singleElement()
                .satisfies(delivery -> assertThat(delivery.processInstanceId()).isEqualTo(first.getId()));
        assertThat(waiting(first)).isFalse();
        assertThat(test.engine.getRuntimeService().getVariable(first.getId(), "paidAmount")).isEqualTo(12.5);
        assertThat(waiting(second)).isTrue();

        InboundEventResult replay = post("payWebhook", "{\"orderId\":\"A-2\",\"amount\":1}", key);
        assertThat(replay.status()).isEqualTo("DUPLICATE");
        assertThat(replay.replayed()).isTrue();
        assertThat(waiting(second)).isTrue();
    }

    @Test
    void noWaitingInstanceReleasesTheKeyForARetry() {
        String key = UUID.randomUUID().toString();

        InboundEventResult result = post("payWebhook", "{\"orderId\":\"NOPE\",\"amount\":1}", key);

        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(result.code()).isEqualTo("NO_WAITING_INSTANCE");
        ProcessInstance late = start("payProcess", "NOPE");
        assertThat(post("payWebhook", "{\"orderId\":\"NOPE\",\"amount\":1}", key).status()).isEqualTo("MATCHED");
        assertThat(waiting(late)).isFalse();
    }

    @Test
    void badBodiesAreRefusedBeforeAnyTrigger() {
        assertThat(post("payWebhook", "{\"orderId\":\"\",\"amount\":1}").code()).isEqualTo("INVALID_CORRELATION");
        assertThat(post("payWebhook", "{\"amount\":1}").code()).isEqualTo("INVALID_CORRELATION");
        assertThat(post("twoKeysHook", "{\"orderId\":\"A-9\"}").code()).isEqualTo("INVALID_CORRELATION");
        InboundEventResult notJson = post("payWebhook", "not json");
        assertThat(notJson.httpStatus()).isEqualTo(400);
        assertThat(notJson.code()).isEqualTo("INVALID_EVENT");
        assertThat(post("payWebhook", "").code()).isEqualTo("INVALID_EVENT");
    }

    @Test
    void callerChannelAndKeyAreChecked() {
        PRINCIPAL.set(new CurrentPrincipal("SERVICE", "intruder", null, "X", "t1", Set.of(), Set.of()));
        assertThat(post("payWebhook", "{\"orderId\":\"A-1\"}").httpStatus()).isEqualTo(403);

        PRINCIPAL.set(new CurrentPrincipal("USER", "u-9", "someone", "X", "t1", Set.of("WF_ADMIN"), Set.of()));
        assertThat(post("payWebhook", "{\"orderId\":\"A-1\"}").httpStatus()).isEqualTo(403);

        PRINCIPAL.set(ERP);
        assertThat(post("missing", "{\"orderId\":\"A-1\"}").code()).isEqualTo("CHANNEL_NOT_FOUND");
        assertThat(post("payWebhook", "{\"orderId\":\"A-1\"}", null).code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(post("payWebhook", "{\"orderId\":\"A-1\"}", "not-a-uuid").httpStatus()).isEqualTo(400);
        assertThat(post("payWebhook", "{\"orderId\":\"A-1\"}", "1-1-1-1-1").httpStatus()).isEqualTo(400);
    }

    @Test
    void anotherTenantDoesNotSeeTheChannel() {
        TENANT.set(OTHER);

        assertThat(post("payWebhook", "{\"orderId\":\"A-1\"}").httpStatus()).isEqualTo(404);
    }

    @Test
    void flagOffHidesEveryChannel() {
        PROPERTIES.setEnabled(false);
        try {
            assertThat(post("payWebhook", "{\"orderId\":\"A-1\"}").code()).isEqualTo("CHANNEL_NOT_FOUND");
        } finally {
            PROPERTIES.setEnabled(true);
        }
    }

    @Test
    void credentialsNeverReachTheEvent() {
        ProcessInstance instance = start("headerProcess", "H-1");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer secret");
        headers.put("Source", "erp");

        InboundEventResult result = receiver.receive("headerHook", headers,
                "{\"orderId\":\"H-1\"}".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());

        assertThat(result.status()).isEqualTo("MATCHED");
        Map<String, Object> variables = test.engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).list().stream()
                .collect(HashMap::new, (map, variable) -> map.put(variable.getVariableName(), variable.getValue()),
                        HashMap::putAll);
        assertThat(variables).containsEntry("src", "erp");
        assertThat(variables.get("auth")).isNull();
        assertThat(variables.values()).noneMatch(value -> String.valueOf(value).contains("secret"));
    }

    @Test
    void keyStaysInProgressWhileHeld() {
        UUID key = UUID.randomUUID();
        String consumer = "event-channel:t1:payWebhook";
        IDEMPOTENCY.claim(consumer, key, "t1", Duration.ofMinutes(1));

        InboundEventResult result = post("payWebhook", "{\"orderId\":\"A-1\"}", key.toString());

        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(result.code()).isEqualTo("EVENT_IN_PROGRESS");
    }

    /** Same semantics as JdbcInboundIdempotency, which InboundIdempotencyIntegrationTest checks on Postgres. */
    static final class FakeIdempotency implements InboundIdempotency {

        private final Map<String, Boolean> completed = new HashMap<>();

        @Override
        public synchronized Claim claim(String consumerName, UUID eventId, String tenantCode, Duration lease) {
            Boolean done = completed.putIfAbsent(consumerName + eventId, false);
            if (done == null) {
                return Claim.CLAIMED;
            }
            return done ? Claim.DUPLICATE : Claim.IN_PROGRESS;
        }

        @Override
        public synchronized void complete(String consumerName, UUID eventId) {
            completed.put(consumerName + eventId, true);
        }

        @Override
        public synchronized void release(String consumerName, UUID eventId) {
            completed.remove(consumerName + eventId, false);
        }

        @Override
        public int purgeCompletedBefore(Duration retention) {
            return 0;
        }
    }
}
