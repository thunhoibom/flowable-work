package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventFieldType;
import io.github.illuseahashmap.workflow.eventregistry.domain.KeyDetectionMode;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code model_json} column of event registry drafts. Event drafts hold
 * {@code {"fields":[...]}}, channel drafts hold the detection, allowed events and security.
 */
final class RegistryDraftJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RegistryDraftJson() {
    }

    static EventField field(EventFieldView view) {
        return new EventField(view.name() == null ? null : view.name().trim(), type(view.type()),
                view.correlation(), view.header(), view.fullPayload());
    }

    static EventFieldView view(EventField field) {
        return new EventFieldView(field.name(), field.type() == null ? null : field.type().flowableName(),
                field.correlation(), field.header(), field.fullPayload());
    }

    /** Null for an unknown type; the rules then report the field as having no type. */
    static EventFieldType type(String name) {
        return Arrays.stream(EventFieldType.values())
                .filter(type -> type.flowableName().equals(name))
                .findFirst().orElse(null);
    }

    static String eventFields(List<EventField> fields) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode array = root.putArray("fields");
        for (EventField field : fields) {
            array.addObject()
                    .put("name", field.name())
                    .put("type", field.type().flowableName())
                    .put("correlation", field.correlation())
                    .put("header", field.header())
                    .put("fullPayload", field.fullPayload());
        }
        return root.toString();
    }

    static List<EventField> eventFields(String json) {
        List<EventField> fields = new ArrayList<>();
        for (JsonNode node : read(json).path("fields")) {
            fields.add(new EventField(node.path("name").asText(), type(node.path("type").asText()),
                    node.path("correlation").asBoolean(), node.path("header").asBoolean(),
                    node.path("fullPayload").asBoolean()));
        }
        return fields;
    }

    static String channel(RestChannelDefinition channel) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("detection", channel.detection().name());
        root.put("detectionValue", channel.detectionValue());
        strings(root.putArray("allowedEventKeys"), channel.allowedEventKeys());
        strings(root.putArray("userIds"), channel.userIds());
        strings(root.putArray("groupIds"), channel.groupIds());
        return root.toString();
    }

    static RestChannelDefinition channel(String key, String name, String description, String json) {
        JsonNode root = read(json);
        return new RestChannelDefinition(key, name, description,
                KeyDetectionMode.valueOf(root.path("detection").asText()), root.path("detectionValue").asText(),
                strings(root.path("allowedEventKeys")), strings(root.path("userIds")), strings(root.path("groupIds")));
    }

    private static void strings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored event registry draft is not valid JSON", exception);
        }
    }
}
