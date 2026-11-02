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
        List<OutputParameter> outputs,
        ContentType contentType,
        String rawMediaType,
        List<StaticHeader> staticHeaders,
        BodyPresence bodyPresence,
        ApiKeyLocation apiKeyLocation,
        AuthDetails authDetails,
        StepConfig steps
) {
    public PublishedOperation {
        errorRules = errorRules == null ? List.of() : List.copyOf(errorRules);
        successStatuses = successStatuses == null ? List.of() : List.copyOf(successStatuses);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        staticHeaders = staticHeaders == null ? List.of() : List.copyOf(staticHeaders);
        // Operations saved before content types existed are JSON calls.
        if (contentType == null) {
            contentType = ContentType.JSON;
        }
        if (bodyPresence == null) {
            bodyPresence = BodyPresence.CONTENT;
        }
        if (apiKeyLocation == null) {
            apiKeyLocation = ApiKeyLocation.HEADER;
        }
        if (authDetails == null) {
            authDetails = AuthDetails.empty();
        }
        if (steps == null) {
            steps = StepConfig.sync();
        }
    }

    /**
     * Constructor used by slice 2. Extended auth fields stay empty so older operations remain JSON calls
     * with a header API key.
     */
    public PublishedOperation(
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
            List<OutputParameter> outputs,
            ContentType contentType,
            String rawMediaType,
            List<StaticHeader> staticHeaders,
            BodyPresence bodyPresence,
            ApiKeyLocation apiKeyLocation) {
        this(
                operationKey,
                method,
                urlTemplate,
                authMode,
                authType,
                apiKeyHeader,
                secret,
                bodyTemplate,
                connectTimeoutMillis,
                readTimeoutMillis,
                errorRules,
                successStatuses,
                inputs,
                outputs,
                contentType,
                rawMediaType,
                staticHeaders,
                bodyPresence,
                apiKeyLocation,
                AuthDetails.empty());
    }

    /** Extended auth without pagination. A missing step config stays one synchronous call. */
    public PublishedOperation(
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
            List<OutputParameter> outputs,
            ContentType contentType,
            String rawMediaType,
            List<StaticHeader> staticHeaders,
            BodyPresence bodyPresence,
            ApiKeyLocation apiKeyLocation,
            AuthDetails authDetails) {
        this(
                operationKey,
                method,
                urlTemplate,
                authMode,
                authType,
                apiKeyHeader,
                secret,
                bodyTemplate,
                connectTimeoutMillis,
                readTimeoutMillis,
                errorRules,
                successStatuses,
                inputs,
                outputs,
                contentType,
                rawMediaType,
                staticHeaders,
                bodyPresence,
                apiKeyLocation,
                authDetails,
                StepConfig.sync());
    }

    /**
     * Constructor used by slice 1. Content stays JSON, the body follows the template,
     * and an API key stays on a header.
     */
    public PublishedOperation(
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
            List<OutputParameter> outputs) {
        this(
                operationKey,
                method,
                urlTemplate,
                authMode,
                authType,
                apiKeyHeader,
                secret,
                bodyTemplate,
                connectTimeoutMillis,
                readTimeoutMillis,
                errorRules,
                successStatuses,
                inputs,
                outputs,
                ContentType.JSON,
                null,
                List.of(),
                BodyPresence.CONTENT,
                ApiKeyLocation.HEADER);
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

    /** Copies resolved credentials onto this operation without dropping content or timeout fields. */
    public PublishedOperation withCredentials(AuthMode mode, AuthConfig auth) {
        AuthConfig resolved = auth == null ? new AuthConfig(AuthType.NONE, null, null) : auth;
        AuthType type = resolved.type() == null ? AuthType.NONE : resolved.type();
        return new PublishedOperation(
                operationKey,
                method,
                urlTemplate,
                mode,
                type,
                resolved.apiKeyHeader(),
                resolved.secret(),
                bodyTemplate,
                connectTimeoutMillis,
                readTimeoutMillis,
                errorRules,
                successStatuses,
                inputs,
                outputs,
                contentType,
                rawMediaType,
                staticHeaders,
                bodyPresence,
                resolved.apiKeyLocation(),
                resolved.details(),
                steps);
    }

    /** Replaces pagination and callback settings without dropping the request contract. */
    public PublishedOperation withSteps(StepConfig steps) {
        return new PublishedOperation(
                operationKey,
                method,
                urlTemplate,
                authMode,
                authType,
                apiKeyHeader,
                secret,
                bodyTemplate,
                connectTimeoutMillis,
                readTimeoutMillis,
                errorRules,
                successStatuses,
                inputs,
                outputs,
                contentType,
                rawMediaType,
                staticHeaders,
                bodyPresence,
                apiKeyLocation,
                authDetails,
                steps == null ? StepConfig.sync() : steps);
    }
}
