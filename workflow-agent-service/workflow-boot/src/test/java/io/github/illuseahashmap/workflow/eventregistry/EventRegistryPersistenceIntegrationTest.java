package io.github.illuseahashmap.workflow.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import io.github.illuseahashmap.workflow.eventregistry.application.EventModelService;
import io.github.illuseahashmap.workflow.eventregistry.application.InboundEventReceiver;
import io.github.illuseahashmap.workflow.eventregistry.application.RestChannelService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.InboundEventResult;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveEventModelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveRestChannelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryDraftRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryReleaseRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.InboundIdempotency;
import io.github.illuseahashmap.workflow.eventregistry.application.port.InboundIdempotency.Claim;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryDraft;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryRelease;
import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Event registry slices 3–5 on PostgreSQL: the V40 repositories, Idempotency-Keys on
 * platform_inbox_event, a webhook delivery through the real Spring beans, and event references.
 */
@SpringBootTest(classes = WorkflowAgentServiceApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventRegistryPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static final TenantContext.TenantInfo TENANT = new TenantContext.TenantInfo("t1", "t1", "Tenant 1");
    static final CurrentPrincipal DESIGNER = new CurrentPrincipal("USER", "u-1", "designer", "Designer", "t1",
            Set.of("WF_ADMIN"), Set.of());
    static final CurrentPrincipal ERP = new CurrentPrincipal("SERVICE", "erp-client", null, "ERP", "t1",
            Set.of("SERVICE"), Set.of());

    @Autowired
    private EventRegistryDraftRepository drafts;
    @Autowired
    private EventRegistryReleaseRepository releases;
    @Autowired
    private EventReferenceRepository references;
    @Autowired
    private InboundIdempotency idempotency;
    @Autowired
    private EventModelService events;
    @Autowired
    private RestChannelService channels;
    @Autowired
    private InboundEventReceiver receiver;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProcessPublishService publishing;
    @Autowired
    private WorkflowAdministrationService administration;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("flowable.database-schema-update", () -> "true");
        registry.add("flowable.async-executor-activate", () -> "false");
        registry.add("workflow.security.enabled", () -> "false");
        registry.add("workflow.auth.keycloak.server-url", () -> "http://127.0.0.1:9");
        registry.add("workflow.auth.keycloak.realm", () -> "rsa");
        registry.add("workflow.auth.keycloak.client-id", () -> "VETC.WORKFLOW");
        registry.add("workflow.runtime.environment", () -> "test");
        registry.add("workflow.event-registry.enabled", () -> "true");
    }

    @BeforeEach
    void signIn() {
        TenantContext.set(TENANT);
        CurrentPrincipalContext.set(DESIGNER);
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
        CurrentPrincipalContext.clear();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Test
    void draftsAreOptimisticAndScopedByTenantAndType() {
        RegistryDraft draft = new RegistryDraft("t1", RegistryModelType.EVENT, "repoEvent", "Repo", null,
                "{\"fields\":[]}", 1, "designer", now());
        assertThat(drafts.insert(draft)).isTrue();
        assertThat(drafts.insert(draft)).isFalse();
        assertThat(drafts.insert(new RegistryDraft("t1", RegistryModelType.CHANNEL, "repoEvent", "Repo", null,
                "{}", 1, "designer", now()))).isTrue();

        RegistryDraft next = draft.next("Repo 2", "mô tả", "{\"fields\":[]}", "designer", now());
        assertThat(drafts.update(next, 1)).isTrue();
        assertThat(drafts.update(next.next("Repo 3", null, "{}", "designer", now()), 1)).isFalse();

        assertThat(drafts.findForUpdate("t1", RegistryModelType.EVENT, "repoEvent"))
                .hasValueSatisfying(found -> {
                    assertThat(found.revision()).isEqualTo(2);
                    assertThat(found.description()).isEqualTo("mô tả");
                });
        assertThat(drafts.list("t1", RegistryModelType.EVENT)).extracting(RegistryDraft::key).contains("repoEvent");
        assertThat(drafts.list("t2", RegistryModelType.EVENT)).isEmpty();

        drafts.delete("t1", RegistryModelType.EVENT, "repoEvent");
        assertThat(drafts.find("t1", RegistryModelType.EVENT, "repoEvent")).isEmpty();
        assertThat(drafts.find("t1", RegistryModelType.CHANNEL, "repoEvent")).isPresent();
    }

    @Test
    void latestReleaseWinsAndUnknownEventsAreUnused() {
        releases.record(new RegistryRelease("t1", RegistryModelType.EVENT, "repoRelease", 1, "d1", "dep1",
                "a".repeat(64), RegistryRelease.PUBLISH, "designer", now().minusMinutes(1)));
        releases.record(new RegistryRelease("t1", RegistryModelType.EVENT, "repoRelease", null, null, null, null,
                RegistryRelease.DELETE, "designer", now()));

        assertThat(releases.latest("t1", RegistryModelType.EVENT, "repoRelease"))
                .hasValueSatisfying(release -> assertThat(release.action()).isEqualTo(RegistryRelease.DELETE));
        assertThat(references.latestProcessDefinitionsUsing("t1", "nobodyUsesThis")).isZero();
    }

    @Test
    void idempotencyKeysFollowTheLeaseAndCompletion() {
        String consumer = "event-channel:t1:leaseHook";
        UUID key = UUID.randomUUID();

        assertThat(idempotency.claim(consumer, key, "t1", Duration.ofMinutes(1))).isEqualTo(Claim.CLAIMED);
        assertThat(idempotency.claim(consumer, key, "t1", Duration.ofMinutes(1))).isEqualTo(Claim.IN_PROGRESS);
        assertThat(idempotency.claim(consumer, key, "t1", Duration.ZERO)).isEqualTo(Claim.CLAIMED);

        idempotency.complete(consumer, key);
        idempotency.release(consumer, key);
        assertThat(idempotency.claim(consumer, key, "t1", Duration.ZERO)).isEqualTo(Claim.DUPLICATE);

        UUID retried = UUID.randomUUID();
        idempotency.claim(consumer, retried, "t1", Duration.ofMinutes(1));
        idempotency.release(consumer, retried);
        assertThat(idempotency.claim(consumer, retried, "t1", Duration.ofMinutes(1))).isEqualTo(Claim.CLAIMED);
    }

    @Test
    void purgeTouchesOnlyCompletedChannelKeys() {
        UUID channelKey = UUID.randomUUID();
        UUID agentKey = UUID.randomUUID();
        idempotency.claim("event-channel:t1:purgeHook", channelKey, "t1", Duration.ofMinutes(1));
        idempotency.complete("event-channel:t1:purgeHook", channelKey);
        jdbc.update("INSERT INTO platform_inbox_event (event_id, consumer_name, tenant_code, received_at, completed_at) "
                + "VALUES (?, 'agent-completion', 't1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", agentKey);

        int removed = TrustedDataAccessContext.runAsSystemWorker(
                () -> idempotency.purgeCompletedBefore(Duration.ZERO));

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(count(channelKey)).isZero();
        assertThat(count(agentKey)).isEqualTo(1);
    }

    private long count(UUID eventId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox_event WHERE event_id = ?",
                Long.class, eventId);
        return count == null ? 0 : count;
    }

    @Test
    void webhookResumesTheWaitingInstanceOnce() {
        long eventRevision = events.saveDraft("itPayment", new SaveEventModelRequest(0, "Đã thanh toán", null,
                List.of(new EventFieldView("orderId", "string", true, false, false),
                        new EventFieldView("amount", "double", false, false, false)))).revision();
        assertThat(events.publish("itPayment", eventRevision).publishedVersion()).isEqualTo(1);
        long channelRevision = channels.saveDraft("itHook", new SaveRestChannelRequest(0, "Webhook", null,
                "FIXED", "itPayment", null, List.of("erp-client"), List.of())).revision();
        assertThat(channels.publish("itHook", channelRevision).publishedVersion()).isEqualTo(1);
        // Deployed directly: this test is about the webhook, not the deploy guard.
        repositoryService.createDeployment().tenantId("t1").addString("itWait.bpmn20.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="itWait" isExecutable="true">
                    <startEvent id="s"/>
                    <receiveTask id="wait">
                      <extensionElements>
                        <flowable:eventType>itPayment</flowable:eventType>
                        <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                        <flowable:eventOutParameter source="amount" target="paidAmount"/>
                      </extensionElements>
                    </receiveTask>
                    <userTask id="after"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>
                  </process>
                </definitions>""").deploy();
        ProcessInstance first = runtimeService.startProcessInstanceByKeyAndTenantId("itWait",
                Map.of("orderId", "IT-1"), "t1");
        ProcessInstance second = runtimeService.startProcessInstanceByKeyAndTenantId("itWait",
                Map.of("orderId", "IT-2"), "t1");
        CurrentPrincipalContext.set(ERP);
        String key = UUID.randomUUID().toString();
        byte[] body = "{\"orderId\":\"IT-1\",\"amount\":99.5}".getBytes(StandardCharsets.UTF_8);

        InboundEventResult result = receiver.receive("itHook", Map.of(), body, key);
        InboundEventResult replay = receiver.receive("itHook", Map.of(), body, key);
        InboundEventResult nobody = receiver.receive("itHook", Map.of(),
                "{\"orderId\":\"NONE\",\"amount\":1}".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());

        assertThat(result.status()).isEqualTo("MATCHED");
        assertThat(runtimeService.getVariable(first.getId(), "paidAmount")).isEqualTo(99.5);
        assertThat(runtimeService.createExecutionQuery().processInstanceId(second.getId()).activityId("wait").count())
                .isEqualTo(1);
        assertThat(replay.status()).isEqualTo("DUPLICATE");
        assertThat(nobody.code()).isEqualTo("NO_WAITING_INSTANCE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox_event WHERE consumer_name = ?",
                Long.class, "event-channel:t1:itHook")).isEqualTo(1);

        TenantContext.set(new TenantContext.TenantInfo("t2", "t2", "Tenant 2"));
        CurrentPrincipalContext.set(ERP);
        assertThat(receiver.receive("itHook", Map.of(), body, UUID.randomUUID().toString()).code())
                .isEqualTo("CHANNEL_NOT_FOUND");
    }

    private static String referenceProcess(String key, boolean waits) {
        String step = waits ? """
                <receiveTask id="pay" flowable:asyncLeave="true">
                  <extensionElements>
                    <flowable:eventType>refEvent</flowable:eventType>
                    <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                  </extensionElements>
                </receiveTask>""" : "<receiveTask id=\"pay\"/>";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="s"/>
                    %s
                    <endEvent id="e"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="pay"/>
                    <sequenceFlow id="f2" sourceRef="pay" targetRef="e"/>
                  </process>
                </definitions>""".formatted(key, key, step);
    }

    private long referenceRows(String eventKey) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_event_reference WHERE event_key = ?",
                Long.class, eventKey);
        return count == null ? 0 : count;
    }

    @Test
    void publishingRecordsEventReferencesThatGuardTheEvent() {
        long revision = events.saveDraft("refEvent", new SaveEventModelRequest(0, "Ref", null,
                List.of(new EventFieldView("orderId", "string", true, false, false)))).revision();
        events.publish("refEvent", revision);

        publishing.publishXml("refProcess", "refProcess", referenceProcess("refProcess", true));

        assertThat(referenceRows("refEvent")).isEqualTo(1);
        assertThat(references.usages("t1", "refEvent")).singleElement().satisfies(usage -> {
            assertThat(usage.processDefinitionKey()).isEqualTo("refProcess");
            assertThat(usage.version()).isEqualTo(1);
            assertThat(usage.activityId()).isEqualTo("pay");
        });
        assertThatThrownBy(() -> events.delete("refEvent"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Quy trình đang dùng sự kiện này");

        ProcessInstance waiting = runtimeService.startProcessInstanceByKeyAndTenantId("refProcess",
                Map.of("orderId", "R-1"), "t1");
        publishing.publishXml("refProcess", "refProcess", referenceProcess("refProcess", false));

        assertThat(references.latestProcessDefinitionsUsing("t1", "refEvent")).isZero();
        assertThat(references.usages("t1", "refEvent")).isEmpty();
        assertThatThrownBy(() -> events.delete("refEvent"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Còn 1 công việc");

        runtimeService.deleteProcessInstance(waiting.getId(), "test");
        administration.deleteProcessDefinitions("refProcess");

        assertThat(referenceRows("refEvent")).isZero();
        events.delete("refEvent");
    }
}
