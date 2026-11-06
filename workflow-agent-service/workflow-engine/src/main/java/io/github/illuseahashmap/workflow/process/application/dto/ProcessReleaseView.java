package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

public record ProcessReleaseView(
        int version,
        String action,
        Integer sourceVersion,
        String performedBy,
        OffsetDateTime performedAt) {
}
