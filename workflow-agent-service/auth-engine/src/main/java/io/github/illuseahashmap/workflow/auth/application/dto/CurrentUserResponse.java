package io.github.illuseahashmap.workflow.auth.application.dto;

import java.util.Set;

/** @param roles EIM role codes of the signed-in user (from the {@code eim_roles} token claim) */
public record CurrentUserResponse(
        String userId,
        String username,
        String displayName,
        String tenantCode,
        Set<String> roles
) {
}
