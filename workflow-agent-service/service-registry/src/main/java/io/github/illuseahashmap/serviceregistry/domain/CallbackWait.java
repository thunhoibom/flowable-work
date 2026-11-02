package io.github.illuseahashmap.serviceregistry.domain;

/** A service task paused until POST /service-callbacks/{correlationId} or the deadline. */
public record CallbackWait(
        String correlationId,
        String executionId,
        String serviceKey,
        String operationKey,
        String outputMappingJson,
        long deadlineEpochMs) {
}
