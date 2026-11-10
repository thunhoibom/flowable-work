package io.github.illuseahashmap.workflow.process.application.dto;

/**
 * One correlation field of an event wait: the expression on the diagram and, when it could be
 * evaluated safely, its value now. {@code evaluated} is false for expressions that are not a plain
 * variable path: those are never re-run, as they could have side effects.
 */
public record WaitCorrelationView(String name, String expression, String currentValue, boolean evaluated) {
}
