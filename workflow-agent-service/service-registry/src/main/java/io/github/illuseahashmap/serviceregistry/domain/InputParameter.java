package io.github.illuseahashmap.serviceregistry.domain;

public record InputParameter(String name, ParameterType type, boolean required, ParameterLocation location) {
}
