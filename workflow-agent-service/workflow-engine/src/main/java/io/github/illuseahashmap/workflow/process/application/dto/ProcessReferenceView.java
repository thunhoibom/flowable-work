package io.github.illuseahashmap.workflow.process.application.dto;

/**
 * One call activity link between two processes.
 *
 * @param processDefinitionKey the other process: the caller for "where used", the callee for "calls"
 * @param published            whether that other process is published right now
 */
public record ProcessReferenceView(
        String processDefinitionKey,
        String processDefinitionName,
        Integer version,
        String activityId,
        boolean published) {
}
