package io.github.illuseahashmap.workflow.eventregistry.domain;

/** One activity of a deployed process version that waits for an event. */
public record EventReference(String processDefinitionId, String activityId, String eventKey) {
}
