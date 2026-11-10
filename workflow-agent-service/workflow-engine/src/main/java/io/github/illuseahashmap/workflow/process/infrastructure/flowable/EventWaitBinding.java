package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import java.util.List;

/**
 * One diagram element that waits for an Event Registry event, as written in the BPMN XML.
 *
 * @param eventKey           trimmed text of flowable:eventType, as Flowable reads it
 * @param correlations       in document order
 * @param hasEventDefinition only for INTERMEDIATE_CATCH and BOUNDARY
 * @param agentTask          only for RECEIVE_TASK
 * @param attachedToRef      only for BOUNDARY
 */
public record EventWaitBinding(
        String activityId,
        Kind kind,
        String eventKey,
        List<Correlation> correlations,
        List<Output> outputs,
        boolean hasEventDefinition,
        boolean agentTask,
        String attachedToRef) {

    public enum Kind { RECEIVE_TASK, INTERMEDIATE_CATCH, BOUNDARY }

    public record Correlation(String name, String value) {
    }

    public record Output(String source, String target, boolean transientValue) {
    }

    public EventWaitBinding {
        correlations = List.copyOf(correlations);
        outputs = List.copyOf(outputs);
    }
}
