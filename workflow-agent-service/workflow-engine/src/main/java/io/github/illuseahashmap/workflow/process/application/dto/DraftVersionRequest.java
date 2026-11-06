package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

public record DraftVersionRequest(@Min(1) int version, @PositiveOrZero long revision) {
}
