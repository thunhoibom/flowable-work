package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

public record PublishedOperation(
        String operationKey,
        HttpMethod method,
        String urlTemplate,
        AuthType authType,
        String apiKeyHeader,
        String secret,
        String bodyTemplate,
        int timeoutMillis,
        List<String> errorStatusCodes,
        String bpmnErrorCode,
        List<InputParameter> inputs,
        List<OutputParameter> outputs
) {
    public PublishedOperation {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }
}
