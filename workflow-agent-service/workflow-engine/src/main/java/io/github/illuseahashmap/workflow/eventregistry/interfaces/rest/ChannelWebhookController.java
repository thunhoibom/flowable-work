package io.github.illuseahashmap.workflow.eventregistry.interfaces.rest;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.InboundEventReceiver;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.InboundEventResult;
import io.github.illuseahashmap.workflow.shared.context.CurrentTraceContext;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook of an inbound REST channel. Any authenticated caller may reach it; the channel's own
 * security then decides. The tenant is the caller's.
 */
@RestController
public class ChannelWebhookController {

    private final InboundEventReceiver receiver;
    private final EventRegistryProperties properties;

    public ChannelWebhookController(InboundEventReceiver receiver, EventRegistryProperties properties) {
        this.receiver = receiver;
        this.properties = properties;
    }

    @PostMapping(path = "/platform-api/channel-definitions/key/{channelKey}/events",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<?>> receive(@PathVariable String channelKey,
                                                  @RequestHeader(value = "Idempotency-Key", required = false)
                                                  String idempotencyKey,
                                                  HttpServletRequest request) throws IOException {
        int max = properties.getMaxBodyBytes();
        if (request.getContentLengthLong() > max) {
            return tooLarge(max);
        }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            return tooLarge(max);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> headers.put(name, request.getHeader(name)));
        InboundEventResult result = receiver.receive(channelKey, headers, body, idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.httpStatus());
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return result.httpStatus() < 300
                ? response.body(ApiResponse.ok(result))
                : response.body(new ApiResponse<>(result.code(), result.message(), result,
                        CurrentTraceContext.currentOrNull()));
    }

    private static ResponseEntity<ApiResponse<?>> tooLarge(int max) {
        return ResponseEntity.status(413).body(ApiResponse.fail("EVENT_TOO_LARGE", "Nội dung vượt " + max + " byte"));
    }
}
