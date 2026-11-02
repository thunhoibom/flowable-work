package io.github.illuseahashmap.serviceregistry.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class CallClassifier {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CallClassifier() {
    }

    public static CallOutcome classify(PublishedOperation operation, int status, String responseBody) {
        return classify(operation, new TransportResponse(status, responseBody));
    }

    public static CallOutcome classify(PublishedOperation operation, TransportResponse response) {
        int status = response.status();
        // Rules are checked before the body so an error status never becomes process variables.
        if (!isSuccess(operation, status)) {
            for (ErrorRule rule : operation.errorRules()) {
                if (HttpStatusMatcher.matches(status, rule.patterns())) {
                    return new CallOutcome(blankToHttpError(rule.bpmnErrorCode()), Map.of());
                }
            }
            return new CallOutcome("HTTP_ERROR", Map.of());
        }
        String contentType = contentType(response.headers());
        boolean file = binary(contentType);
        // Text and XML stay a string. A file is base64 so the bytes survive a process variable.
        String rawBody = file && response.rawBody() != null
                ? Base64.getEncoder().encodeToString(response.rawBody())
                : response.body();
        JsonNode body = file ? null : readObject(response.body());
        Map<String, Object> variables = new LinkedHashMap<>();
        for (OutputParameter output : operation.outputs()) {
            OutputSource source = output.source() == null ? OutputSource.JSON_FIELD : output.source();
            switch (source) {
                case JSON_FIELD -> copyJsonField(variables, body, output);
                case RAW_BODY -> {
                    if (rawBody != null) {
                        variables.put(output.name(), rawBody);
                    }
                }
                case STATUS -> variables.put(output.name(), status);
                case RESPONSE_HEADER -> copyHeader(variables, response.headers(), output);
            }
        }
        return new CallOutcome(null, variables);
    }

    /** A body that is not a JSON object leaves JSON fields empty. Text and XML still fill RAW_BODY. */
    private static void copyJsonField(Map<String, Object> variables, JsonNode body, OutputParameter output) {
        if (body == null || !body.isObject() || output.jsonField() == null) {
            return;
        }
        JsonNode node = body.get(output.jsonField());
        if (node == null || node.isNull()) {
            return;
        }
        variables.put(output.name(), JSON.convertValue(node, Object.class));
    }

    private static void copyHeader(Map<String, Object> variables, Map<String, String> headers, OutputParameter output) {
        if (output.headerName() == null || headers == null) {
            return;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() != null && header.getKey().equalsIgnoreCase(output.headerName())) {
                variables.put(output.name(), header.getValue() == null ? "" : header.getValue());
                return;
            }
        }
    }

    public static boolean binary(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (type.startsWith("text/") || type.endsWith("+json") || type.endsWith("+xml")) {
            return false;
        }
        return !type.equals("application/json")
                && !type.equals("application/xml")
                && !type.equals("application/x-www-form-urlencoded");
    }

    public static String contentType(Map<String, String> headers) {
        if (headers == null) {
            return "";
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() != null && header.getKey().equalsIgnoreCase("content-type")) {
                return header.getValue() == null ? "" : header.getValue();
            }
        }
        return "";
    }

    public static Charset charset(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        for (String piece : contentType.split(";")) {
            String part = piece.trim();
            if (part.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Charset.forName(part.substring("charset=".length()).trim());
                } catch (RuntimeException exception) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 2xx is always success. Extra statuses are declared successes such as a 404 that still returns a body. */
    private static boolean isSuccess(PublishedOperation operation, int status) {
        return (status >= 200 && status < 300) || HttpStatusMatcher.matches(status, operation.successStatuses());
    }

    private static String blankToHttpError(String bpmnErrorCode) {
        return bpmnErrorCode == null || bpmnErrorCode.isBlank() ? "HTTP_ERROR" : bpmnErrorCode;
    }

    private static JsonNode readObject(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
