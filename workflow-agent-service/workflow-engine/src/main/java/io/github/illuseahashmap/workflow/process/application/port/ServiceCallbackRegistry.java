package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Map;

/** Parks one service task until a webhook with the same correlation id arrives. */
public interface ServiceCallbackRegistry {

    void register(
            String correlationId,
            String executionId,
            String serviceKey,
            String operationKey,
            Map<String, String> outputVariables,
            long waitTimeoutMillis);
}
