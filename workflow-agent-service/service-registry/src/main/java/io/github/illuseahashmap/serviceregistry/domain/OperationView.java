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
        List<String> successStatuses,
        ContentType contentType,
        String rawMediaType,
        List<StaticHeader> staticHeaders,
        BodyPresence bodyPresence,
        ApiKeyLocation apiKeyLocation,
        String bearerVariable,
        String tokenUrl,
        String clientId,
        List<String> scopes,
        boolean certificatePresent,
        String signatureHeader,
        boolean accessKeyPresent,
        String region,
        String awsService,
        String publishedContentType,
        StepConfig steps,
        StepConfig publishedSteps
) {
    public OperationView {
        errorRules = errorRules == null ? List.of() : List.copyOf(errorRules);
        successStatuses = successStatuses == null ? List.of() : List.copyOf(successStatuses);
        staticHeaders = staticHeaders == null ? List.of() : List.copyOf(staticHeaders);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        if (authMode == null) {
            authMode = AuthMode.OVERRIDE;
        }
        if (contentType == null) {
            contentType = ContentType.JSON;
        }
        if (bodyPresence == null) {
            bodyPresence = BodyPresence.CONTENT;
        }
        if (apiKeyLocation == null) {
            apiKeyLocation = ApiKeyLocation.HEADER;
        }
    }

    /** View used before extended auth was shown. Those fields stay empty. */
    public OperationView(
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
            List<String> successStatuses,
            ContentType contentType,
            String rawMediaType,
            List<StaticHeader> staticHeaders,
            BodyPresence bodyPresence,
            ApiKeyLocation apiKeyLocation) {
        this(
                operationKey,
                method,
                urlTemplate,
                authType,
                apiKeyHeader,
                secretPresent,
                bodyTemplate,
                timeoutMillis,
                errorStatusCodes,
                bpmnErrorCode,
                publishedUrl,
                publishedMethod,
                publishedBpmnErrorCode,
                inputParameters,
                outputParameters,
                publishedInputParameters,
                publishedOutputParameters,
                authMode,
                connectTimeoutMillis,
                errorRules,
                successStatuses,
                contentType,
                rawMediaType,
                staticHeaders,
                bodyPresence,
                apiKeyLocation,
                null,
                null,
                null,
                List.of(),
                false,
                null,
                false,
                null,
                null,
                null,
                StepConfig.sync(),
                null);
    }
}
