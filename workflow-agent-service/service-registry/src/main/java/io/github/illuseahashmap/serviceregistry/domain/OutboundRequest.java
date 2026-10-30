package io.github.illuseahashmap.serviceregistry.domain;

import java.util.Map;

public record OutboundRequest(
        HttpMethod method,
        String url,
        Map<String, String> headers,
        String body,
        int timeoutMillis
) {
}
