package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventFieldType;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.KeyDetectionMode;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelDefinition;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.RestInboundChannelModel;
import java.util.List;
import org.flowable.eventregistry.json.converter.ChannelJsonConverter;
import org.flowable.eventregistry.json.converter.EventJsonConverter;
import org.flowable.eventregistry.model.EventModel;
import org.junit.jupiter.api.Test;

class FlowableEventRegistryJsonTest {

    private static final EventModelDefinition MODEL = new EventModelDefinition("paymentReceived", "Đã nhận",
            "mô tả", List.of(
                    new EventField("orderId", EventFieldType.STRING, true, false, false),
                    new EventField("source", EventFieldType.STRING, false, true, false),
                    new EventField("amount", EventFieldType.DOUBLE, false, false, false),
                    new EventField("raw", EventFieldType.JSON, false, false, true)));

    private static RestInboundChannelModel parse(String json) {
        ChannelJsonConverter converter = new ChannelJsonConverter();
        converter.addInboundChannelModelClass("rest", RestInboundChannelModel.class);
        return (RestInboundChannelModel) converter.convertToChannelModel(json);
    }

    @Test
    void producesJsonFlowableParsesBackToTheSameFields() {
        String json = FlowableEventRegistryJson.event(MODEL);

        EventModel parsed = new EventJsonConverter().convertToEventModel(json);

        assertThat(parsed.getKey()).isEqualTo("paymentReceived");
        assertThat(parsed.getName()).isEqualTo("Đã nhận");
        assertThat(FlowableEventRegistryJson.fields(parsed)).containsExactlyElementsOf(MODEL.fields());
    }

    @Test
    void sameModelGivesSameHash() {
        assertThat(FlowableEventRegistryJson.sha256(FlowableEventRegistryJson.event(MODEL)))
                .isEqualTo(FlowableEventRegistryJson.sha256(FlowableEventRegistryJson.event(MODEL)))
                .hasSize(64);
    }

    @Test
    void fixedChannelIsAJsonRestChannelPinnedToTheTenant() {
        RestInboundChannelModel model = parse(FlowableEventRegistryJson.channel(new RestChannelDefinition(
                "payWebhook", "Webhook", null, KeyDetectionMode.FIXED, "paymentReceived",
                List.of("ignored"), List.of("erp-client"), List.of("WF_ADMIN")), "VETC_TNHH"));

        assertThat(model.getKey()).isEqualTo("payWebhook");
        assertThat(model.getDeserializerType()).isEqualTo("json");
        assertThat(model.getChannelEventKeyDetection().getFixedValue()).isEqualTo("paymentReceived");
        assertThat(model.getChannelEventTenantIdDetection().getFixedValue()).isEqualTo("VETC_TNHH");
        assertThat(model.getAllowedEventKeys()).containsExactly("paymentReceived");
        assertThat(model.getSecurity().getUserIds()).containsExactly("erp-client");
        assertThat(model.getSecurity().getGroupIds()).containsExactly("WF_ADMIN");
    }

    @Test
    void keyDetectionFollowsTheMode() {
        RestInboundChannelModel field = parse(FlowableEventRegistryJson.channel(new RestChannelDefinition(
                "c", "C", null, KeyDetectionMode.JSON_FIELD, "type",
                List.of("a", "b"), List.of("u"), List.of()), "t1"));
        RestInboundChannelModel pointer = parse(FlowableEventRegistryJson.channel(new RestChannelDefinition(
                "c", "C", null, KeyDetectionMode.JSON_POINTER, "/meta/type",
                List.of("a"), List.of(), List.of("g")), "t1"));

        assertThat(field.getChannelEventKeyDetection().getJsonField()).isEqualTo("type");
        assertThat(field.getAllowedEventKeys()).containsExactly("a", "b");
        assertThat(pointer.getChannelEventKeyDetection().getJsonPointerExpression()).isEqualTo("/meta/type");
        assertThat(pointer.getSecurity().getGroupIds()).containsExactly("g");
    }
}
