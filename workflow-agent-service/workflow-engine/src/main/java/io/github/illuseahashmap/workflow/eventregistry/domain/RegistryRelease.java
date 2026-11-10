package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.time.OffsetDateTime;

/** One publish or delete of an event or channel model. */
public record RegistryRelease(
        String tenantId,
        RegistryModelType type,
        String key,
        Integer flowableVersion,
        String flowableDefinitionId,
        String flowableDeploymentId,
        String deployedJsonSha256,
        String action,
        String performedBy,
        OffsetDateTime performedAt) {

    public static final String PUBLISH = "PUBLISH";
    public static final String DELETE = "DELETE";

    public boolean isPublish() {
        return PUBLISH.equals(action);
    }
}
