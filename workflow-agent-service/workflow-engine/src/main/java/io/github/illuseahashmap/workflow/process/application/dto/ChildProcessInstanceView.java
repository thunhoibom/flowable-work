package io.github.illuseahashmap.workflow.process.application.dto;

/** An instance started by a call activity of the instance being viewed. */
public record ChildProcessInstanceView(
        String callActivityId,
        String callActivityName,
        ProcessInstanceSummaryView instance) {
}
