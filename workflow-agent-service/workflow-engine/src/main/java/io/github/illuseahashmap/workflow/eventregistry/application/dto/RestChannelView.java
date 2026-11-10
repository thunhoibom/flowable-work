package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** An inbound REST channel: its draft, what is published, and where external systems post. */
public record RestChannelView(
        String key,
        String name,
        String description,
        String detection,
        String detectionValue,
        List<String> allowedEventKeys,
        List<String> userIds,
        List<String> groupIds,
        long revision,
        String updatedBy,
        OffsetDateTime updatedAt,
        Integer publishedVersion,
        OffsetDateTime publishedAt,
        String publishedBy,
        boolean changed,
        String webhookPath) {

    public static String webhookPath(String key) {
        return "/platform-api/channel-definitions/key/" + key + "/events";
    }
}
