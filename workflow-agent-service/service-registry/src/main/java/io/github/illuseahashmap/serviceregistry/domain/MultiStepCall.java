package io.github.illuseahashmap.serviceregistry.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Page walking for one service-task run. Cookies collected here are not stored anywhere else. */
final class MultiStepCall {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MultiStepCall() {
    }

    static StepConfig steps(PublishedOperation operation) {
        return operation.steps() == null ? StepConfig.sync() : operation.steps();
    }

    /** An absolute next link is used as-is. Anything else is resolved against the URL just sent. */
    static String resolveNextUrl(String requestUrl, String next) {
        String value = next == null ? "" : next.trim();
        if (value.startsWith("https://") || value.startsWith("http://")) {
            return value;
        }
        try {
            return URI.create(requestUrl).resolve(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new InvalidServiceRequestException("Next URL is invalid");
        }
    }

    static String nextText(String body, String field) {
        JsonNode node = fieldNode(body, field);
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    /** The token keeps the input's type so a numeric page token is not sent as a quoted string. */
    static Object nextToken(String body, String field, ParameterType type) {
        JsonNode node = fieldNode(body, field);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return switch (type == null ? ParameterType.STRING : type) {
                case STRING -> blankToNull(node.asText());
                case BOOLEAN -> node.isBoolean() ? node.booleanValue() : Boolean.parseBoolean(node.asText());
                case INTEGER, LONG -> node.isNumber() ? node.longValue() : Long.parseLong(node.asText());
                case DOUBLE -> node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText());
                case JSON -> node.isValueNode() ? node.asText() : JSON.convertValue(node, Object.class);
            };
        } catch (RuntimeException exception) {
            throw new InvalidServiceRequestException("Next token does not match the input type");
        }
    }

    /** JSON arrays are concatenated. Every other output keeps the last page that produced it. */
    static void merge(Map<String, Object> combined, Map<String, Object> page) {
        for (Map.Entry<String, Object> entry : page.entrySet()) {
            Object value = entry.getValue();
            Object previous = combined.get(entry.getKey());
            if (value instanceof List<?> items && (previous == null || previous instanceof List<?>)) {
                List<Object> all = new ArrayList<>();
                if (previous instanceof List<?> existing) {
                    all.addAll(existing);
                }
                all.addAll(items);
                combined.put(entry.getKey(), List.copyOf(all));
            } else {
                combined.put(entry.getKey(), value);
            }
        }
    }

    private static JsonNode fieldNode(String body, String field) {
        if (body == null || body.isBlank() || field == null || field.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) {
                return null;
            }
            return root.get(field);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Names and values from Set-Cookie. Only the requests that follow in this run receive them. */
    static final class CookieJar {

        private final Map<String, String> cookies = new LinkedHashMap<>();

        void absorb(List<String> setCookies) {
            if (setCookies == null) {
                return;
            }
            for (String header : setCookies) {
                if (header == null || header.isBlank()) {
                    continue;
                }
                String pair = header.split(";", 2)[0].trim();
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String name = pair.substring(0, separator).trim();
                if (!name.isEmpty()) {
                    cookies.put(name, pair.substring(separator + 1).trim());
                }
            }
        }

        boolean isEmpty() {
            return cookies.isEmpty();
        }

        String header() {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, String> cookie : cookies.entrySet()) {
                if (!builder.isEmpty()) {
                    builder.append("; ");
                }
                builder.append(cookie.getKey()).append('=').append(cookie.getValue());
            }
            return builder.toString();
        }
    }
}
