package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventFieldType;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.KeyDetectionMode;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FlowableEventRegistryGatewayTest {

    private static final List<EventField> FIELDS = List.of(
            new EventField("orderId", EventFieldType.STRING, true, false, false),
            new EventField("amount", EventFieldType.DOUBLE, false, false, false));

    private static EventRegistryTestEngine test;

    @BeforeAll
    static void start() {
        test = new EventRegistryTestEngine("event-registry-gateway");
    }

    @AfterAll
    static void stop() {
        test.close();
    }

    private static String json(String key) {
        return FlowableEventRegistryJson.event(new EventModelDefinition(key, "Paid", null, FIELDS));
    }

    @Test
    void deploysVersionsPerTenantAndDeletesThem() {
        assertThat(test.gateway.deployEvent("t1", "paid", json("paid")).version()).isEqualTo(1);
        assertThat(test.gateway.deployEvent("t1", "paid", json("paid")).version()).isEqualTo(2);

        assertThat(test.gateway.publishedEventFields("t1", "paid")).contains(FIELDS);
        assertThat(test.gateway.publishedEventFields("t2", "paid")).isEmpty();

        test.gateway.deleteDeployments("t1", RegistryModelType.EVENT, "paid");
        assertThat(test.gateway.publishedEventFields("t1", "paid")).isEmpty();
    }

    @Test
    void channelIsRegisteredForItsTenantOnlyAndGoneAfterDelete() {
        test.gateway.deployEvent("t1", "chanEvent", json("chanEvent"));
        var deployed = test.gateway.deployChannel("t1", "chanHook", FlowableEventRegistryJson.channel(
                new RestChannelDefinition("chanHook", "Hook", null, KeyDetectionMode.FIXED, "chanEvent",
                        null, List.of("erp-client"), List.of()), "t1"));

        assertThat(deployed.version()).isEqualTo(1);
        assertThat(test.gateway.channelDeployed("t1", "chanHook")).isTrue();
        assertThat(test.gateway.channelDeployed("t2", "chanHook")).isFalse();
        assertThat(test.channels.find("t1", "chanHook")).isPresent();
        assertThat(test.channels.find("t2", "chanHook")).isEmpty();

        test.gateway.deleteDeployments("t1", RegistryModelType.CHANNEL, "chanHook");
        assertThat(test.gateway.channelDeployed("t1", "chanHook")).isFalse();
        assertThat(test.channels.find("t1", "chanHook")).isEmpty();
    }

    @Test
    void countsWaitingSubscriptionsOfTheTenant() {
        test.gateway.deployEvent("t1", "waitEvent", json("waitEvent"));
        test.deployWaitingProcess("t1", "waitProcess", "waitEvent");
        test.engine.getRuntimeService().startProcessInstanceByKeyAndTenantId("waitProcess",
                Map.of("orderId", "A-1"), "t1");

        assertThat(test.gateway.waitingSubscriptions("t1", "waitEvent")).isEqualTo(1);
        assertThat(test.gateway.waitingSubscriptions("t2", "waitEvent")).isZero();
    }
}
