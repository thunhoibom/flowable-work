package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.time.OffsetDateTime;

/** The editable copy of an event or channel model. Flowable never sees it until it is published. */
public record RegistryDraft(
        String tenantId,
        RegistryModelType type,
        String key,
        String name,
        String description,
        String modelJson,
        long revision,
        String updatedBy,
        OffsetDateTime updatedAt) {

    public RegistryDraft next(String newName, String newDescription, String newModelJson, String actor,
                              OffsetDateTime now) {
        return new RegistryDraft(tenantId, type, key, newName, newDescription, newModelJson, revision + 1,
                actor, now);
    }
}
