package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A step the instance is currently waiting at: a manual wait continued by "Tiếp tục", or an
 * Event Registry wait resumed by an inbound event.
 *
 * @param kind               MANUAL or EVENT
 * @param elementType        receiveTask, intermediateCatchEvent or boundaryEvent
 * @param eventKey           null for MANUAL
 * @param correlationChanged whether the correlation values differ from those the wait started with;
 *                           null when they cannot be checked
 * @param dueDate            earliest timer of a timer boundary on the step, else null
 */
public record ProcessWaitView(
        String kind,
        String activityId,
        String activityName,
        String executionId,
        OffsetDateTime since,
        String elementType,
        String eventKey,
        List<WaitCorrelationView> correlations,
        Boolean correlationChanged,
        OffsetDateTime dueDate
) {
    public static final String MANUAL = "MANUAL";
    public static final String EVENT = "EVENT";

    public ProcessWaitView {
        correlations = correlations == null ? List.of() : List.copyOf(correlations);
    }

    public static ProcessWaitView manual(String activityId, String activityName, String executionId,
                                         OffsetDateTime since, OffsetDateTime dueDate) {
        return new ProcessWaitView(MANUAL, activityId, activityName, executionId, since, "receiveTask", null,
                List.of(), null, dueDate);
    }
}
