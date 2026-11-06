package io.github.illuseahashmap.workflow.process.domain;

import java.time.OffsetDateTime;

/** One lifecycle event of a published process version. */
public record ProcessRelease(
        Long id,
        String tenantId,
        String processDefinitionKey,
        String processDefinitionId,
        int version,
        ReleaseAction action,
        Integer sourceVersion,
        String xmlSha256,
        String performedBy,
        OffsetDateTime performedAt) {
}
