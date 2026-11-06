package io.github.illuseahashmap.workflow.process.domain;

import java.time.OffsetDateTime;

/** A draft without its XML, used by list pages. */
public record ProcessDraftHeader(
        String processDefinitionKey,
        String name,
        String xmlSha256,
        long revision,
        String updatedBy,
        OffsetDateTime updatedAt) {
}
