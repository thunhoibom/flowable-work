package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.util.List;

/** A business event the platform can receive: its key, name and fields. */
public record EventModelDefinition(String key, String name, String description, List<EventField> fields) {

    public EventModelDefinition {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
