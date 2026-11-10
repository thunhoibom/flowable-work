package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import java.time.OffsetDateTime;

/** One process execution of the tenant currently waiting for an event. */
public record EventWaitView(
        String processInstanceId,
        String processDefinitionKey,
        String processDefinitionName,
        String businessKey,
        String activityId,
        String activityName,
        String executionId,
        OffsetDateTime since) {
}
