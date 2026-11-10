package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import java.util.List;

/** A latest process version that waits for an event, and the steps that do. */
public record EventUsageView(String processDefinitionKey, int version, List<String> activityIds) {

    public EventUsageView {
        activityIds = List.copyOf(activityIds);
    }
}
