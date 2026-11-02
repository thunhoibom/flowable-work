package io.github.illuseahashmap.serviceregistry.domain;

/**
 * Credentials resolved for one call. The secret stays in memory and is never written to process variables.
 */
public record AuthConfig(
        AuthType type,
        String apiKeyHeader,
        String secret,
        ApiKeyLocation apiKeyLocation,
        AuthDetails details) {
    public AuthConfig {
        if (apiKeyLocation == null) {
            apiKeyLocation = ApiKeyLocation.HEADER;
        }
        if (details == null) {
            details = AuthDetails.empty();
        }
    }

    public AuthConfig(AuthType type, String apiKeyHeader, String secret, ApiKeyLocation apiKeyLocation) {
        this(type, apiKeyHeader, secret, apiKeyLocation, AuthDetails.empty());
    }

    public AuthConfig(AuthType type, String apiKeyHeader, String secret) {
        this(type, apiKeyHeader, secret, ApiKeyLocation.HEADER, AuthDetails.empty());
    }
}
