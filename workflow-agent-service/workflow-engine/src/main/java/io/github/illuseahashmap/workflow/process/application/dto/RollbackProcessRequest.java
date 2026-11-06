package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.Min;

public record RollbackProcessRequest(@Min(1) int version) {
}
