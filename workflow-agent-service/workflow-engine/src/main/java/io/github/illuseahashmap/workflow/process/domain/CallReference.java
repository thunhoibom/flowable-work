package io.github.illuseahashmap.workflow.process.domain;

/** One call activity in one published process version. */
public record CallReference(
        String parentDefinitionId,
        String parentDefinitionKey,
        int parentVersion,
        String activityId,
        String calledKey) {
}
