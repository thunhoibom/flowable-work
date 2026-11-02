package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

/**
 * One published operation. Timeouts are split so a slow server can use a longer read timeout
 * than the connect timeout. Existing callers still use the 12-argument constructor.
 */
public record PublishedOperation(
        String operationKey,
        HttpMethod method,
        String urlTemplate,
        AuthMode authMode,
        AuthType authType,
        String apiKeyHeader,
        String secret,
        String bodyTemplate,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        List<ErrorRule> errorRules,
        List<String> successStatuses,
        List<InputParameter> inputs,
        List<OutputParameter> outputs
) {
    public PublishedOperation {
        errorRules = errorRules == null ? List.of() : List.copyOf(errorRules);
        successStatuses = successStatuses == null ? List.of() : List.copyOf(successStatuses);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }

    /**
     * Compatibility constructor for catalogs that stored one status list and one BPMN code.
     * Auth stays on the operation ({@link AuthMode#OVERRIDE}) and both timeouts share the old value.
     */
    public PublishedOperation(
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
            List<OutputParameter> outputs) {
        this(
                operationKey,
                method,
                urlTemplate,
                AuthMode.OVERRIDE,
                authType,
                apiKeyHeader,
                secret,
                bodyTemplate,
                timeoutMillis,
                timeoutMillis,
                List.of(new ErrorRule(errorStatusCodes, bpmnErrorCode)),
                List.of(),
                inputs,
                outputs);
    }

    /** Read timeout. Kept so existing request and persistence code does not change in this slice. */
    public int timeoutMillis() {
        return readTimeoutMillis;
    }

    /** Patterns from the first rule, which is the migrated single error list. */
    public List<String> errorStatusCodes() {
        return errorRules.isEmpty() ? List.of() : errorRules.getFirst().patterns();
    }

    /** BPMN code from the first rule. Blank codes are classified as HTTP_ERROR later. */
    public String bpmnErrorCode() {
        return errorRules.isEmpty() ? null : errorRules.getFirst().bpmnErrorCode();
    }
}
