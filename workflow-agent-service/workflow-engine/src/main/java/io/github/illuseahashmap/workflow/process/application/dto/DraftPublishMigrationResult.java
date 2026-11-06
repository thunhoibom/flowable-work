package io.github.illuseahashmap.workflow.process.application.dto;

import java.util.List;

/** Per-key outcome of converting a tenant from the active-version table to draft/publish. */
public record DraftPublishMigrationResult(boolean dryRun, List<Item> items) {

    public DraftPublishMigrationResult {
        items = List.copyOf(items);
    }

    public enum Scenario {
        /** The key already has release rows: nothing to do. */
        ALREADY_MIGRATED,
        /** The active version is the latest: only a draft and a release row are added. */
        ACTIVE_IS_LATEST,
        /** Unpublished versions sit above the active one: the active XML is redeployed on top. */
        DRAFT_AHEAD,
        /** No active version: every definition is suspended so nothing can start. */
        NEVER_PUBLISHED
    }

    public record Item(String processDefinitionKey, Scenario scenario, String action,
                       Integer newVersion, String error) {
    }
}
