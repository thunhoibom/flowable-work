package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.InboundEventReceiver;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.InboundEventResult;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.application.port.InboundIdempotency;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.InboundDispatchContext;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.RestInboundChannelModel;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.RestInboundChannelModelProcessor;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRegistryEvent;
import org.flowable.eventregistry.api.InboundEventProcessingPipeline;
import org.flowable.eventregistry.api.runtime.EventInstance;
import org.flowable.eventregistry.api.runtime.EventPayloadInstance;
import org.flowable.eventregistry.impl.DefaultInboundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs an event posted to a REST channel through the channel's own Flowable pipeline, checks it,
 * then hands it to the waiting process instances.
 *
 * <p>Not transactional on purpose: every trigger is its own Flowable command, so one failing
 * instance does not roll back the others (R-D2). The tenant is the caller's, never the body's (R-C4).
 * Logs never contain payload or correlation values.
 */
@Service
public class InboundEventReceiverImpl implements InboundEventReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboundEventReceiverImpl.class);
    private static final Set<String> HIDDEN_HEADERS = Set.of("authorization", "cookie", "x-workflow-token");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventRegistryProperties properties;
    private final TenantProvider tenants;
    private final CurrentPrincipalProvider principals;
    private final RestInboundChannelModelProcessor channels;
    private final EventRegistryGateway gateway;
    private final EventRegistry eventRegistry;
    private final InboundIdempotency idempotency;

    public InboundEventReceiverImpl(EventRegistryProperties properties, TenantProvider tenants,
                                    CurrentPrincipalProvider principals, RestInboundChannelModelProcessor channels,
                                    EventRegistryGateway gateway, EventRegistry eventRegistry,
                                    InboundIdempotency idempotency) {
        this.properties = properties;
        this.tenants = tenants;
        this.principals = principals;
        this.channels = channels;
        this.gateway = gateway;
        this.eventRegistry = eventRegistry;
        this.idempotency = idempotency;
    }

    @Override
    public InboundEventResult receive(String channelKey, Map<String, String> headers, byte[] body, String key) {
        if (!properties.isEnabled()) {
            return notFound(channelKey);
        }
        TenantContext.TenantInfo tenant = tenants.current();
        Optional<RestInboundChannelModel> found = channels.find(tenant.tenantId(), channelKey);
        if (found.isEmpty()) {
            return gateway.channelDeployed(tenant.tenantId(), channelKey)
                    ? InboundEventResult.fail(503, "CHANNEL_NOT_READY", "Kênh đang được nạp, hãy thử lại sau")
                    : notFound(channelKey);
        }
        RestInboundChannelModel channel = found.get();
        if (!allowed(channel.getSecurity(), principals.current())) {
            return InboundEventResult.fail(403, "CHANNEL_FORBIDDEN", "Không có quyền gửi vào kênh " + channelKey);
        }
        UUID eventId = eventId(key);
        if (eventId == null) {
            return InboundEventResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED", "Header Idempotency-Key phải là UUID");
        }
        String consumer = JdbcInboundIdempotency.CONSUMER_PREFIX + tenant.tenantId() + ":" + channelKey;
        switch (idempotency.claim(consumer, eventId, tenant.tenantCode(), properties.getInProgressLease())) {
            case DUPLICATE -> {
                log(tenant, channelKey, eventId, "DUPLICATE", 0, 0);
                return new InboundEventResult(200, "DUPLICATE", null, null, List.of(), List.of(), true);
            }
            case IN_PROGRESS -> {
                return InboundEventResult.fail(409, "EVENT_IN_PROGRESS", "Sự kiện này đang được xử lý");
            }
            case CLAIMED -> {
                // continue below
            }
        }
        InboundEventResult result = null;
        try {
            result = dispatch(tenant, channel, headers, body);
            return result;
        } finally {
            if (result != null && result.delivered()) {
                idempotency.complete(consumer, eventId);
            } else {
                idempotency.release(consumer, eventId);
            }
            log(tenant, channelKey, eventId, result == null ? "EVENT_PROCESSING_FAILED" : result.status(),
                    result == null ? 0 : result.matched().size(), result == null ? 0 : result.failed().size());
        }
    }

    private InboundEventResult dispatch(TenantContext.TenantInfo tenant, RestInboundChannelModel channel,
                                        Map<String, String> headers, byte[] body) {
        String json = new String(body, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return InboundEventResult.fail(400, "INVALID_EVENT", "Nội dung sự kiện đang trống");
        }
        try {
            MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            return InboundEventResult.fail(400, "INVALID_EVENT", "Nội dung sự kiện không phải JSON hợp lệ");
        }
        Collection<EventRegistryEvent> events;
        try {
            var pipeline = (InboundEventProcessingPipeline) channel.getInboundEventProcessingPipeline();
            events = pipeline.run(channel, new DefaultInboundEvent(json, visibleHeaders(headers)));
        } catch (RuntimeException exception) {
            LOGGER.debug("Inbound event on channel {} was not recognised: {}", channel.getKey(),
                    exception.getClass().getSimpleName());
            return InboundEventResult.fail(422, "EVENT_NOT_RECOGNIZED", "Không nhận ra sự kiện trong nội dung");
        }
        if (events.isEmpty()) {
            return InboundEventResult.fail(422, "EVENT_NOT_RECOGNIZED", "Không nhận ra sự kiện trong nội dung");
        }
        for (EventRegistryEvent event : events) {
            if (!(event.getEventObject() instanceof EventInstance instance)) {
                return InboundEventResult.fail(422, "EVENT_NOT_RECOGNIZED", "Không nhận ra sự kiện trong nội dung");
            }
            InboundEventResult invalid = validate(tenant, channel, instance);
            if (invalid != null) {
                return invalid;
            }
        }
        try (InboundDispatchContext context = InboundDispatchContext.open()) {
            events.forEach(eventRegistry::sendEventToConsumers);
            return result(context);
        }
    }

    private InboundEventResult validate(TenantContext.TenantInfo tenant, RestInboundChannelModel channel,
                                        EventInstance instance) {
        if (!channel.getAllowedEventKeys().contains(instance.getEventKey())) {
            return InboundEventResult.fail(422, "EVENT_NOT_ALLOWED", "Kênh không nhận sự kiện " + instance.getEventKey());
        }
        if (!tenant.tenantId().equals(instance.getTenantId())) {
            return InboundEventResult.fail(422, "EVENT_NOT_ALLOWED", "Sự kiện không thuộc tổ chức hiện tại");
        }
        // A correlation field missing from the body is simply absent from the instance, so compare
        // with the model instead of only checking the values that are there.
        Map<String, Object> values = instance.getCorrelationParameterInstances().stream()
                .collect(Collectors.toMap(EventPayloadInstance::getDefinitionName,
                        payload -> payload.getValue() == null ? "" : payload.getValue(), (first, second) -> first));
        List<String> expected = gateway.publishedEventFields(tenant.tenantId(), instance.getEventKey())
                .orElse(List.of()).stream().filter(EventField::correlation).map(EventField::name).toList();
        if (expected.isEmpty()) {
            return InboundEventResult.fail(422, "INVALID_CORRELATION", "Sự kiện không có trường tương quan");
        }
        for (String name : expected) {
            Object value = values.get(name);
            if (value == null || value.toString().isBlank()) {
                return InboundEventResult.fail(422, "INVALID_CORRELATION", "Trường tương quan " + name + " đang trống");
            }
        }
        return null;
    }

    private static InboundEventResult result(InboundDispatchContext context) {
        List<InboundEventResult.Delivery> matched = new ArrayList<>();
        List<InboundEventResult.Delivery> failed = new ArrayList<>();
        for (InboundDispatchContext.Outcome outcome : context.outcomes()) {
            var delivery = new InboundEventResult.Delivery(outcome.processInstanceId(), outcome.activityId(),
                    outcome.errorCode());
            (outcome.succeeded() ? matched : failed).add(delivery);
        }
        return switch (context.result()) {
            case MATCHED -> new InboundEventResult(200, "MATCHED", null, null, matched, failed, false);
            case PARTIAL -> new InboundEventResult(200, "PARTIAL", null, null, matched, failed, false);
            case NO_MATCH -> InboundEventResult.fail(409, "NO_WAITING_INSTANCE",
                    "Không có công việc nào đang chờ sự kiện này");
            case FAILED -> failed.stream().allMatch(delivery -> "CONCURRENT_UPDATE".equals(delivery.errorCode()))
                    ? new InboundEventResult(409, "CONCURRENT_UPDATE", "CONCURRENT_UPDATE",
                            "Công việc đang được cập nhật, hãy thử lại", matched, failed, false)
                    : new InboundEventResult(500, "EVENT_PROCESSING_FAILED", "EVENT_PROCESSING_FAILED",
                            "Xử lý sự kiện thất bại", matched, failed, false);
        };
    }

    static boolean allowed(RestInboundChannelModel.Security security, CurrentPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (security.getUserIds().contains(principal.principalId())
                || (principal.username() != null && security.getUserIds().contains(principal.username()))) {
            return true;
        }
        return principal.roles().stream().anyMatch(security.getGroupIds()::contains);
    }

    /**
     * Headers the event model may read. Values are bytes so Flowable converts them to the header
     * field's type. Credentials never reach the event.
     */
    static Map<String, Object> visibleHeaders(Map<String, String> headers) {
        Map<String, Object> visible = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headers.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .filter(entry -> !HIDDEN_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT)))
                    .forEach(entry -> visible.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        return visible;
    }

    private static UUID eventId(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            UUID id = UUID.fromString(key.trim());
            // UUID.fromString accepts shortened forms such as "1-1-1-1-1"; require the canonical text.
            return id.toString().equalsIgnoreCase(key.trim()) ? id : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static InboundEventResult notFound(String channelKey) {
        return InboundEventResult.fail(404, "CHANNEL_NOT_FOUND", "Không có kênh " + channelKey);
    }

    private static void log(TenantContext.TenantInfo tenant, String channelKey, UUID eventId, String status,
                            int matched, int failed) {
        LOGGER.info("Inbound event tenant={} channel={} idempotencyKey={} status={} matched={} failed={}",
                tenant.tenantId(), channelKey, eventId, status, matched, failed);
    }
}
