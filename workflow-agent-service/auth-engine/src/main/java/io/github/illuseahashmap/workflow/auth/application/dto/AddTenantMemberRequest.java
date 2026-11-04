package io.github.illuseahashmap.workflow.auth.application.dto;

import jakarta.validation.constraints.NotBlank;

/** Adds an SSO user to the current tenant; roles are assigned in EIM, not here. */
public record AddTenantMemberRequest(@NotBlank String username) {
}
