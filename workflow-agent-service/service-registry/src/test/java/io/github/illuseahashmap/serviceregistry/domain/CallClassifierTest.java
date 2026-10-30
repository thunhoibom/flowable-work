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
}
