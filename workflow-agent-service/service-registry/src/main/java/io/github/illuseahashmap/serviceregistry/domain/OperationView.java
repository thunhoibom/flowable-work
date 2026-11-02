package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

public record OperationView(
        String operationKey,
        HttpMethod method,
        String urlTemplate,
        AuthType authType,
        String apiKeyHeader,
        boolean secretPresent,
        String bodyTemplate,
        int timeoutMillis,
        List<String> errorStatusCodes,
        String bpmnErrorCode,
        String publishedUrl,
        HttpMethod publishedMethod,
        String publishedBpmnErrorCode,
        List<InputParameter> inputParameters,
        List<OutputParameter> outputParameters,
        List<InputParameter> publishedInputParameters,
        List<OutputParameter> publishedOutputParameters,
        AuthMode authMode,
        int connectTimeoutMillis,
        List<ErrorRule> errorRules,
        List<String> successStatuses
) {
    public OperationView {
        errorRules = errorRules == null ? List.of() : List.copyOf(errorRules);
        successStatuses = successStatuses == null ? List.of() : List.copyOf(successStatuses);
        if (authMode == null) {
            authMode = AuthMode.OVERRIDE;
        }
    }
}
