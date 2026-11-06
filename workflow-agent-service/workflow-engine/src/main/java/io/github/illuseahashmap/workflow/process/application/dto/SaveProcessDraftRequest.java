package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record SaveProcessDraftRequest(
        @NotBlank String name,
        @NotBlank String bpmnXml,
        @PositiveOrZero long revision) {
}
