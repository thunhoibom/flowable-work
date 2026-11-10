package io.github.illuseahashmap.workflow.eventregistry.application.dto;

/** One field of an event model. {@code type} is the Flowable type name: string, integer, long, double, boolean, json. */
public record EventFieldView(String name, String type, boolean correlation, boolean header, boolean fullPayload) {
}
