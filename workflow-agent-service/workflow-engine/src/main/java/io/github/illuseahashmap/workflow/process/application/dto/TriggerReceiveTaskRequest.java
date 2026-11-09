package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * Continue a manual wait step.
 *
 * @param executionId required when the step is multi-instance and several branches are waiting
 */
public record TriggerReceiveTaskRequest(
        @NotBlank String processInstanceId,
        @NotBlank String activityId,
        String executionId,
        Map<String, Object> variables,
        @NotBlank String reason
) {
}
