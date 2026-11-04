package io.github.illuseahashmap.workflow.auth.application.dto;

public record TenantOptionResponse(
        String tenantId,
        String tenantCode,
        String tenantName,
        boolean enabled,
        boolean current
) {
}
