package io.github.illuseahashmap.workflow.serviceregistry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.serviceregistry.application.PublishedServiceInvoker;
import io.github.illuseahashmap.serviceregistry.domain.CallClassifier;
import io.github.illuseahashmap.serviceregistry.domain.CallbackWait;
import io.github.illuseahashmap.serviceregistry.domain.CallbackWaitStore;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.TransportResponse;
import io.github.illuseahashmap.workflow.process.application.ParameterCoercion;
import io.github.illuseahashmap.workflow.process.application.ServiceParameterException;
import io.github.illuseahashmap.workflow.process.application.ServiceParameterType;
import io.github.illuseahashmap.workflow.process.application.port.ServiceCallbackRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The callback body is classified as the operation response. The HTTP status of the callback
 * request itself is not the upstream status, so mapping always starts from 200.
 */
@Service
public class ServiceCallbackService implements ServiceCallbackRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCallbackService.class);
    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() {
    };

    private final CallbackWaitStore waits;
    private final PublishedServiceInvoker invoker;
    private final ServiceCallbackSignals signals;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ServiceCallbackService(
            CallbackWaitStore waits, PublishedServiceInvoker invoker, ServiceCallbackSignals signals) {
        this(waits, invoker, signals, Clock.systemUTC());
    }

    ServiceCallbackService(
            CallbackWaitStore waits, PublishedServiceInvoker invoker, ServiceCallbackSignals signals, Clock clock) {
        this.waits = waits;
        this.invoker = invoker;
        this.signals = signals;
        this.clock = clock;
    }

    @Override
    public void register(
            String correlationId,
            String executionId,
            String serviceKey,
            String operationKey,
            Map<String, String> outputVariables,
            long waitTimeoutMillis) {
        String id = requireCorrelationId(correlationId);
        if (waitTimeoutMillis <= 0) {
            throw new ServiceParameterException(
                    "service " + serviceKey + " operation " + operationKey + ": Wait timeout is required");
        }
        try {
            waits.insert(new CallbackWait(
                    id,
                    executionId,
                    serviceKey,
                    operationKey,
                    objectMapper.writeValueAsString(outputVariables == null ? Map.of() : outputVariables),
                    clock.millis() + waitTimeoutMillis));
        } catch (IllegalStateException exception) {
            throw new ServiceParameterException(
                    "service " + serviceKey + " operation " + operationKey + ": Correlation id is already waiting");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Unable to store callback mapping", exception);
        }
    }

    /** @return false when no task is waiting for this id */
    public boolean complete(String correlationId, String body, Map<String, String> headers, byte[] rawBody) {
        Optional<CallbackWait> taken = waits.take(correlationId);
        if (taken.isEmpty()) {
            return false;
        }
        CallbackWait wait = taken.get();
        try {
            PublishedOperation operation = invoker.findPublished(wait.serviceKey(), wait.operationKey())
                    .orElseThrow(() -> new ServiceParameterException("SERVICE_NOT_PUBLISHED"));
            String text = body == null ? "" : body;
            byte[] raw = rawBody == null ? text.getBytes(StandardCharsets.UTF_8) : rawBody;
            var outcome = CallClassifier.classify(
                    operation, new TransportResponse(200, text, headers == null ? Map.of() : headers, raw));
            signals.trigger(wait.executionId(), variables(operation, readMapping(wait.outputMappingJson()), outcome.variables()));
            return true;
        } catch (RuntimeException exception) {
            // The wait row is already gone. Leave the task with the same code as a lost connection.
            LOGGER.warn("Service callback failed correlationId={}", wait.correlationId(), exception);
            signals.fail(wait.executionId());
            return true;
        }
    }

    @Scheduled(fixedDelayString = "${workflow.services.callback-expire-ms:5000}")
    public void expire() {
        for (CallbackWait wait : waits.takeExpired(clock.millis())) {
            signals.fail(wait.executionId());
        }
    }

    private Map<String, Object> variables(
            PublishedOperation operation, Map<String, String> mapping, Map<String, Object> result) {
        Map<String, Object> variables = new LinkedHashMap<>();
        operation.outputs().forEach(output -> {
            String variable = mapping.get(output.name());
            if (variable == null || variable.isBlank() || !result.containsKey(output.name())) {
                return;
            }
            Object value = ParameterCoercion.coerce(ServiceParameterType.valueOf(output.type().name()), result.get(output.name()));
            if (value != null) {
                variables.put(variable, value);
            }
        });
        return variables;
    }

    private Map<String, String> readMapping(String json) {
        try {
            Map<String, String> mapping = objectMapper.readValue(json, STRINGS);
            return mapping == null ? Map.of() : mapping;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read callback mapping", exception);
        }
    }

    private static String requireCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 256) {
            throw new ServiceParameterException("Correlation id is required");
        }
        // The id is a path segment. A slash would address a different resource.
        if (correlationId.indexOf('/') >= 0 || correlationId.indexOf('?') >= 0 || correlationId.indexOf('#') >= 0
                || correlationId.chars().anyMatch(Character::isWhitespace)) {
            throw new ServiceParameterException("Correlation id is required");
        }
        return correlationId;
    }
}
