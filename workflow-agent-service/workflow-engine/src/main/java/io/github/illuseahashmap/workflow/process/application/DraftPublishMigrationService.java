package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.DraftPublishMigrationResult;

/** One-off, idempotent conversion of the current tenant. */
public interface DraftPublishMigrationService {

    DraftPublishMigrationResult run(boolean dryRun);
}
