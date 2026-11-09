package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

/** A step the instance is currently waiting at. Slice 2 only reports manual waits. */
public record ProcessWaitView(
        String kind,
        String activityId,
        String activityName,
        String executionId,
        OffsetDateTime since
) {
    public static final String MANUAL = "MANUAL";
}
