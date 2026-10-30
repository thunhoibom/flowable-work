package io.github.illuseahashmap.serviceregistry.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RequestFactory {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RequestFactory() {
    }

    public static OutboundRequest create(PublishedOperation operation, Map<String, Object> resolvedInputs) {
        Map<String, Object> values = resolvedInputs == null ? Map.of() : resolvedInputs;
        String url = operation.urlTemplate();
        StringBuilder query = new StringBuilder();
        Map<String, String> headers = new LinkedHashMap<>();
        boolean hasBody = operation.inputs().stream()
                .anyMatch(input -> input.location() == ParameterLocation.BODY);
        BodyDocument body = hasBody ? readBody(operation.bodyTemplate()) : null;
        for (InputParameter input : operation.inputs()) {
            Object raw = values.get(input.name());
            boolean present = raw != null;
            switch (input.location()) {
                case PATH -> {
                    if (present) {
                        url = url.replace("${" + input.name() + "}", encodePath(textual(input.type(), raw)));
                    }
                }
                case QUERY -> {
                    if (present) {
                        appendQuery(query, input.name(), textual(input.type(), raw));
                    }
                }
                case HEADER -> {
                    if (present) {
                        headers.put(input.name(), textual(input.type(), raw));
                    }
                }
                case BODY -> {
                    if (present) {
                        body.set(input.name(), JSON.valueToTree(raw));
                    } else {
                        body.remove(input.name());
                    }
                }
            }
        }
        if (url.contains("${")) {
            throw new InvalidServiceRequestException("URL still contains a placeholder");
        }
        if (!query.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + query;
        }
        applyAuth(headers, operation);
        String payload = hasBody ? writeBody(body) : operation.bodyTemplate();
        if (payload != null && !payload.isBlank() && !hasHeader(headers, "Content-Type")) {
            headers.put("Content-Type", "application/json");
        }
        return new OutboundRequest(
                operation.method(),
                url,
                headers,
                payload,
                operation.timeoutMillis());
    }

    private static void applyAuth(Map<String, String> headers, PublishedOperation operation) {
        if (operation.secret() == null || operation.authType() == AuthType.NONE) {
            return;
        }
        switch (operation.authType()) {
            case BEARER -> headers.put("Authorization", "Bearer " + operation.secret());
            case BASIC -> headers.put("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(operation.secret().getBytes(StandardCharsets.UTF_8)));
            case API_KEY -> headers.put(operation.apiKeyHeader(), operation.secret());
            case NONE -> {
            }
        }
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        for (String header : headers.keySet()) {
            if (header.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String textual(ParameterType type, Object value) {
        return switch (type) {
            case STRING -> (String) value;
            case BOOLEAN -> Boolean.TRUE.equals(value) ? "true" : "false";
            case INTEGER, LONG -> Long.toString(((Number) value).longValue());
            case DOUBLE -> Double.toString(((Number) value).doubleValue());
            case JSON -> compact(value);
        };
    }

    private static BodyDocument readBody(String template) {
        String document = template == null || template.isBlank() ? "{}" : template;
        try {
            JsonNode node = JSON.readTree(document);
            if (node instanceof ObjectNode object) {
                return new BodyDocument(object, null);
            }
            if (node instanceof ArrayNode array && !array.isEmpty()) {
                for (JsonNode item : array) {
                    if (!(item instanceof ObjectNode)) {
                        throw new InvalidServiceRequestException("body template is not a JSON object");
                    }
                }
                return new BodyDocument(null, array);
            }
        } catch (JsonProcessingException exception) {
            throw new InvalidServiceRequestException("body template is not a JSON object");
        }
        throw new InvalidServiceRequestException("body template is not a JSON object");
    }

    private static String writeBody(BodyDocument body) {
        try {
            return JSON.writeValueAsString(body.root());
        } catch (JsonProcessingException exception) {
            throw new InvalidServiceRequestException("body template is not a JSON object");
        }
    }

    private static final class BodyDocument {
        private final ObjectNode object;
        private final ArrayNode array;

        private BodyDocument(ObjectNode object, ArrayNode array) {
            this.object = object;
            this.array = array;
        }

        private void set(String name, JsonNode value) {
            for (ObjectNode item : objects()) {
                item.set(name, value.deepCopy());
            }
        }

        private void remove(String name) {
            for (ObjectNode item : objects()) {
                item.remove(name);
            }
        }

        private JsonNode root() {
            return array == null ? object : array;
        }

        private List<ObjectNode> objects() {
            if (array == null) {
                return List.of(object);
            }
            List<ObjectNode> items = new ArrayList<>();
            for (JsonNode item : array) {
                items.add((ObjectNode) item);
            }
            return items;
        }
    }

    private static String compact(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new InvalidServiceRequestException("parameter value cannot be written");
        }
    }

    private static void appendQuery(StringBuilder query, String name, String value) {
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(encodeQuery(name)).append('=').append(encodeQuery(value));
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
