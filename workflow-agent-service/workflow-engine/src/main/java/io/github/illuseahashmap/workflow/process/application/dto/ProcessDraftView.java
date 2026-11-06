package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

/**
 * A process as the designer edits it. {@code revision == 0} means no draft row exists yet
 * and the XML comes from the latest deployed version.
 */
public record ProcessDraftView(
        String processDefinitionKey,
        String name,
        String bpmnXml,
        long revision,
        String updatedBy,
        OffsetDateTime updatedAt,
        Integer latestVersion,
        boolean suspended,
        boolean hasUnpublishedChanges) {
}
