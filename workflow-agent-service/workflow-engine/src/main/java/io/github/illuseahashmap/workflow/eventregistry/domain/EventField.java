package io.github.illuseahashmap.workflow.eventregistry.domain;

/** One field of an event model, as Flowable's EventPayload. */
public record EventField(String name, EventFieldType type, boolean correlation, boolean header, boolean fullPayload) {
}
