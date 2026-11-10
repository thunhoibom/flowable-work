package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventFieldType;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.flowable.eventregistry.model.EventModel;

/** The JSON Flowable 8 deploys for event and channel models, built in a stable order. */
public final class FlowableEventRegistryJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowableEventRegistryJson() {
    }

    public static String event(EventModelDefinition model) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("key", model.key());
        root.put("name", model.name());
        ArrayNode payload = root.putArray("payload");
        for (EventField field : model.fields()) {
            ObjectNode node = payload.addObject();
            node.put("name", field.name());
            node.put("type", field.type().flowableName());
            if (field.correlation()) {
                node.put("correlationParameter", true);
            }
            if (field.header()) {
                node.put("header", true);
            }
            if (field.fullPayload()) {
                node.put("isFullPayload", true);
            }
        }
        return root.toString();
    }

    /**
     * A REST inbound channel. The tenant is fixed to the publishing tenant and never read from the
     * message (R-C4).
     */
    public static String channel(RestChannelDefinition channel, String tenantId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("key", channel.key());
        root.put("category", "channel");
        root.put("name", channel.name());
        root.put("channelType", "inbound");
        root.put("type", "rest");
        root.put("deserializerType", "json");
        ObjectNode detection = root.putObject("channelEventKeyDetection");
        switch (channel.detection()) {
            case FIXED -> detection.put("fixedValue", channel.detectionValue());
            case JSON_FIELD -> detection.put("jsonField", channel.detectionValue());
            case JSON_POINTER -> detection.put("jsonPointerExpression", channel.detectionValue());
        }
        root.putObject("channelEventTenantIdDetection").put("fixedValue", tenantId);
        ArrayNode allowed = root.putArray("allowedEventKeys");
        channel.allowedEventKeys().forEach(allowed::add);
        ObjectNode security = root.putObject("security");
        ArrayNode users = security.putArray("userIds");
        channel.userIds().forEach(users::add);
        ArrayNode groups = security.putArray("groupIds");
        channel.groupIds().forEach(groups::add);
        return root.toString();
    }

    public static List<EventField> fields(EventModel model) {
        return model.getPayload().stream()
                .map(payload -> new EventField(payload.getName(), EventFieldType.fromFlowable(payload.getType()),
                        payload.isCorrelationParameter(), payload.isHeader(), payload.isFullPayload()))
                .toList();
    }

    public static String sha256(String json) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
