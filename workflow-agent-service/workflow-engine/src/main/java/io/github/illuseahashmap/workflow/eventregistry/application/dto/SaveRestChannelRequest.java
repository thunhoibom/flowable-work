package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Saves a REST channel draft. {@code revision = 0} creates it. {@code detection} is FIXED,
 * JSON_FIELD or JSON_POINTER; with FIXED the allowed events are just {@code detectionValue}.
 */
public record SaveRestChannelRequest(
        @PositiveOrZero long revision,
        @NotBlank @Size(max = 256) String name,
        @Size(max = 1024) String description,
        @NotBlank String detection,
        @NotBlank @Size(max = 256) String detectionValue,
        List<String> allowedEventKeys,
        List<String> userIds,
        List<String> groupIds) {
}
