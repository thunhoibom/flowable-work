package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProcessDraftRequest(
        @NotBlank String processDefinitionKey,
        @NotBlank String name,
        @NotBlank String bpmnXml) {
}
