package io.github.illuseahashmap.serviceregistry.domain;

/**
 * One mapped output. Older parameters only named a JSON field, so a missing source stays {@code JSON_FIELD}.
 */
public record OutputParameter(
        String name,
        ParameterType type,
        String jsonField,
        OutputSource source,
        String headerName) {

    public OutputParameter {
        if (source == null) {
            source = OutputSource.JSON_FIELD;
        }
    }

    public OutputParameter(String name, ParameterType type, String jsonField) {
        this(name, type, jsonField, OutputSource.JSON_FIELD, null);
    }
}
