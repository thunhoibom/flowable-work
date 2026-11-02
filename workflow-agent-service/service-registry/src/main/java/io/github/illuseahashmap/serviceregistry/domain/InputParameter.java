package io.github.illuseahashmap.serviceregistry.domain;

public record InputParameter(
        String name,
        ParameterType type,
        boolean required,
        ParameterLocation location,
        PartKind partKind
) {
    public InputParameter {
        // Older saved parameters have no part kind. Text matches a normal form field.
        if (partKind == null) {
            partKind = PartKind.TEXT;
        }
    }

    public InputParameter(String name, ParameterType type, boolean required, ParameterLocation location) {
        this(name, type, required, location, PartKind.TEXT);
    }
}
