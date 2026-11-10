package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.util.List;
import java.util.Objects;

/** An inbound REST channel (webhook): how it finds the event key, which events and callers it accepts. */
public record RestChannelDefinition(
        String key,
        String name,
        String description,
        KeyDetectionMode detection,
        String detectionValue,
        List<String> allowedEventKeys,
        List<String> userIds,
        List<String> groupIds) {

    public RestChannelDefinition {
        if (detection == KeyDetectionMode.FIXED) {
            allowedEventKeys = detectionValue == null || detectionValue.isBlank()
                    ? List.of() : List.of(detectionValue);
        } else {
            allowedEventKeys = copy(allowedEventKeys);
        }
        userIds = copy(userIds);
        groupIds = copy(groupIds);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().toList();
    }
}
