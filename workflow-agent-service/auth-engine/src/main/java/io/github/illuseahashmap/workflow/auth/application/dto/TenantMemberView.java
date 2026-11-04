package io.github.illuseahashmap.workflow.auth.application.dto;

import java.time.OffsetDateTime;

public record TenantMemberView(
        String userId,
        String username,
        String displayName,
        boolean enabled,
        OffsetDateTime joinedAt
) {
}
