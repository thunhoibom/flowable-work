package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** The draft revision the caller has seen. A newer draft is never published by accident. */
public record PublishProcessRequest(@PositiveOrZero long revision) {
}
