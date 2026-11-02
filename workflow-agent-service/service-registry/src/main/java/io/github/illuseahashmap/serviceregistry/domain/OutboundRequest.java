package io.github.illuseahashmap.serviceregistry.domain;

import java.util.Map;

public record OutboundRequest(
        HttpMethod method,
        String url,
        Map<String, String> headers,
        String body,
        int timeoutMillis,
        byte[] binaryBody,
        MutualTlsIdentity clientIdentity
) {
    public OutboundRequest(
            HttpMethod method, String url, Map<String, String> headers, String body, int timeoutMillis, byte[] binaryBody) {
        this(method, url, headers, body, timeoutMillis, binaryBody, null);
    }

    public OutboundRequest(HttpMethod method, String url, Map<String, String> headers, String body, int timeoutMillis) {
        this(method, url, headers, body, timeoutMillis, null, null);
    }
}
