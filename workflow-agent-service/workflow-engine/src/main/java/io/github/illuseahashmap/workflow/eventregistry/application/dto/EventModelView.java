package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * An event model: its draft, and what is published in Flowable. {@code changed} is true when the
 * draft has never been published or differs from the published content.
 */
public record EventModelView(
        String key,
        String name,
        String description,
        List<EventFieldView> fields,
        long revision,
        String updatedBy,
        OffsetDateTime updatedAt,
        Integer publishedVersion,
        OffsetDateTime publishedAt,
        String publishedBy,
        List<EventFieldView> publishedFields,
        boolean changed) {
}
