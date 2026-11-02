package io.github.illuseahashmap.serviceregistry.domain;

/**
 * Credentials resolved for one call. The secret stays in memory and is never written to process variables.
 */
public record AuthConfig(AuthType type, String apiKeyHeader, String secret) {
}
