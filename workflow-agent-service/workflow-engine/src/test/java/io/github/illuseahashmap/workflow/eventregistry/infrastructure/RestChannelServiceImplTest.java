package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.RestChannelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveEventModelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveRestChannelRequest;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestChannelServiceImplTest {

    private static EventRegistryTestEngine test;
    private EventModelServiceImpl events;
    private RestChannelServiceImpl channels;

    @BeforeAll
    static void start() {
        test = new EventRegistryTestEngine("rest-channel-service");
    }

    @AfterAll
    static void stop() {
        test.close();
    }

    @BeforeEach
    void setUp() {
        var repositories = new InMemoryEventRegistryRepositories();
        var properties = new EventRegistryProperties();
        properties.setEnabled(true);
        var audit = mock(WorkflowOperationAuditService.class);
        events = new EventModelServiceImpl(repositories.drafts, repositories.releases, repositories.references,
                test.gateway, audit, () -> EventModelServiceImplTest.TENANT, () -> EventModelServiceImplTest.DESIGNER,
                properties);
        channels = new RestChannelServiceImpl(repositories.drafts, repositories.releases, test.gateway, audit,
                () -> EventModelServiceImplTest.TENANT, () -> EventModelServiceImplTest.DESIGNER, properties);
    }

    private void publishEvent(String key) {
        long revision = events.saveDraft(key, new SaveEventModelRequest(0, "Paid", null,
                List.of(EventModelServiceImplTest.ORDER_ID, EventModelServiceImplTest.AMOUNT))).revision();
        events.publish(key, revision);
    }

    private RestChannelView saveChannel(String key, String eventKey) {
        return channels.saveDraft(key, new SaveRestChannelRequest(0, "Webhook", null, "FIXED", eventKey,
                null, List.of("erp-client"), List.of()));
    }

    private static long versions(String key) {
        return test.events.getEventRepositoryService().createChannelDefinitionQuery()
                .channelDefinitionKey(key).tenantId("t1").count();
    }

    @Test
    void channelNeedsItsEventsPublished() {
        RestChannelView draft = saveChannel("hookEarly", "notYetPublished");

        assertThatThrownBy(() -> channels.publish("hookEarly", draft.revision()))
                .hasMessageContaining("Sự kiện notYetPublished chưa được phát hành");
        assertThat(versions("hookEarly")).isZero();
    }

    @Test
    void publishedChannelIsRegisteredOnceAndDeleteUnregistersIt() {
        publishEvent("hookEvent");
        RestChannelView draft = saveChannel("hookLive", "hookEvent");
        assertThat(draft.allowedEventKeys()).containsExactly("hookEvent");
        assertThat(draft.webhookPath()).isEqualTo("/platform-api/channel-definitions/key/hookLive/events");

        RestChannelView published = channels.publish("hookLive", draft.revision());
        channels.publish("hookLive", draft.revision());

        assertThat(published.publishedVersion()).isEqualTo(1);
        assertThat(published.changed()).isFalse();
        assertThat(versions("hookLive")).isEqualTo(1);
        assertThat(test.channels.find("t1", "hookLive"))
                .hasValueSatisfying(model -> assertThat(model.getInboundEventProcessingPipeline()).isNotNull());

        channels.delete("hookLive");
        assertThat(versions("hookLive")).isZero();
        assertThat(test.channels.find("t1", "hookLive")).isEmpty();
    }

    @Test
    void eventUsedByAChannelCannotBeDeleted() {
        publishEvent("hookUsed");
        saveChannel("hookUser", "hookUsed");

        assertThatThrownBy(() -> events.delete("hookUsed")).hasMessageContaining("Kênh hookUser đang dùng sự kiện này");
    }

    @Test
    void invalidChannelIsRefused() {
        assertThatThrownBy(() -> channels.saveDraft("hookBad", new SaveRestChannelRequest(0, "Webhook", null,
                "SOMETHING", "x", null, List.of(), List.of())))
                .hasMessageContaining("nhận diện")
                .hasMessageContaining("người hoặc vai trò");
    }
}
