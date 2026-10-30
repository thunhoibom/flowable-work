package io.github.illuseahashmap.workflow.process.domain;

public record ServiceTaskBinding(
        String activityId,
        String serviceKey,
        String operationKey,
        String inputMappingJson,
        String outputMappingJson
) {
}
