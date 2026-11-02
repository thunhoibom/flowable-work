package io.github.illuseahashmap.serviceregistry.domain;

/** One environment name and the base URL used when an operation path is relative. */
public record EnvironmentBaseUrl(String environment, String url) {
}
