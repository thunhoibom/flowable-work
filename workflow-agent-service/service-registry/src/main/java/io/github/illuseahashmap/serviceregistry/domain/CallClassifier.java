package io.github.illuseahashmap.serviceregistry.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CallClassifier {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CallClassifier() {
    }

    public static CallOutcome classify(PublishedOperation operation, int status, String responseBody) {
        // Rules are checked before the body so an error status never becomes process variables.
        if (!isSuccess(operation, status)) {
            for (ErrorRule rule : operation.errorRules()) {
                if (HttpStatusMatcher.matches(status, rule.patterns())) {
                    return new CallOutcome(blankToHttpError(rule.bpmnErrorCode()), Map.of());
                }
            }
            return new CallOutcome("HTTP_ERROR", Map.of());
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        JsonNode body = readObject(responseBody);
        if (body != null && body.isObject()) {
            for (OutputParameter output : operation.outputs()) {
                JsonNode node = body.get(output.jsonField());
                if (node == null || node.isNull()) {
                    continue;
                }
                variables.put(output.name(), JSON.convertValue(node, Object.class));
            }
        }
        return new CallOutcome(null, variables);
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
