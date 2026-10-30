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
        if (status < 200 || status >= 300) {
            String code = HttpStatusMatcher.matches(status, operation.errorStatusCodes())
                    ? operation.bpmnErrorCode()
                    : "HTTP_ERROR";
            if (code == null || code.isBlank()) {
                code = "HTTP_ERROR";
            }
            return new CallOutcome(code, Map.of());
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
