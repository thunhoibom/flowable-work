package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record DraftRevisionRequest(@PositiveOrZero long revision) {
}
