package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.util.Arrays;

/** Flowable 8 EventPayloadTypes. */
public enum EventFieldType {
    STRING("string"), INTEGER("integer"), LONG("long"), DOUBLE("double"), BOOLEAN("boolean"), JSON("json");

    private final String flowableName;

    EventFieldType(String flowableName) {
        this.flowableName = flowableName;
    }

    public String flowableName() {
        return flowableName;
    }

    public boolean correlatable() {
        return this == STRING || this == INTEGER || this == LONG;
    }

    public static EventFieldType fromFlowable(String name) {
        return Arrays.stream(values()).filter(type -> type.flowableName.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown event field type " + name));
    }
}
