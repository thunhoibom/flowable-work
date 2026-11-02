package io.github.illuseahashmap.serviceregistry.domain;

import java.util.Map;

public record TransportResponse(
        int status, String body, Map<String, String> headers, byte[] rawBody, java.util.List<String> setCookies) {

    public TransportResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        // Set-Cookie is kept as separate values. Joining them would split on commas inside Expires.
        setCookies = setCookies == null ? java.util.List.of() : java.util.List.copyOf(setCookies);
    }

    public TransportResponse(int status, String body, Map<String, String> headers, byte[] rawBody) {
        this(status, body, headers, rawBody, java.util.List.of());
    }

    public TransportResponse(int status, String body) {
        this(status, body, Map.of(), null, java.util.List.of());
    }
}
