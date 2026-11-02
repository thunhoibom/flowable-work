package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CallClassifierTest {

    @Test
    void readsTopLevelFieldsAndSkipsNulls() {
        var operation = new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthType.NONE, null, null, null, 1000, List.of("409"), "LEAD_CONFLICT",
                List.of(),
                List.of(
                        new OutputParameter("id", ParameterType.STRING, "id"),
                        new OutputParameter("customer", ParameterType.JSON, "customer"),
                        new OutputParameter("missing", ParameterType.STRING, "missing"),
                        new OutputParameter("empty", ParameterType.STRING, "note")));
        var success = CallClassifier.classify(operation, 201, """
                {"id":"L1","customer":{"name":"Ann"},"items":[1],"note":null}
                """);
        assertFalse(success.failed());
        assertEquals("L1", success.variables().get("id"));
        assertEquals("Ann", ((Map<?, ?>) success.variables().get("customer")).get("name"));
        assertFalse(success.variables().containsKey("missing"));
        assertFalse(success.variables().containsKey("empty"));

        assertEquals("LEAD_CONFLICT", CallClassifier.classify(operation, 409, "{}").bpmnErrorCode());
        assertEquals("HTTP_ERROR", CallClassifier.classify(operation, 404, "{}").bpmnErrorCode());
    }

    @Test
    void usesTheFirstMatchingRuleAndTreatsDeclaredStatusesAsSuccess() {
        var operation = new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthMode.OVERRIDE, AuthType.NONE, null, null, null,
                1000, 2500,
                java.util.List.of(
                        new ErrorRule(java.util.List.of("409"), "LEAD_CONFLICT"),
                        new ErrorRule(java.util.List.of("5xx"), "UNAVAILABLE")),
                java.util.List.of("404"),
                java.util.List.of(),
                java.util.List.of(new OutputParameter("id", ParameterType.STRING, "id")));
        // 404 is declared as success, so the body is still mapped into variables.
        assertFalse(CallClassifier.classify(operation, 404, "{\"id\":\"L1\"}").failed());
        assertEquals("L1", CallClassifier.classify(operation, 404, "{\"id\":\"L1\"}").variables().get("id"));
        // The first matching rule wins; 503 falls through to the class rule.
        assertEquals("LEAD_CONFLICT", CallClassifier.classify(operation, 409, "{}").bpmnErrorCode());
        assertEquals("UNAVAILABLE", CallClassifier.classify(operation, 503, "{}").bpmnErrorCode());
        assertEquals("HTTP_ERROR", CallClassifier.classify(operation, 401, "{}").bpmnErrorCode());
    }
}
