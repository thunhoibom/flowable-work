package io.github.illuseahashmap.serviceregistry.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches an OAuth2 client-credentials token and keeps it until 30 seconds before it expires.
 * A failed token request is an IO failure so the call becomes SERVICE_UNAVAILABLE.
 */
public final class OauthClientCredentials {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpTransport transport;
    private final Clock clock;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public OauthClientCredentials(HttpTransport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    public String accessToken(PublishedOperation operation) throws IOException {
        AuthDetails details = operation.authDetails();
        if (details.tokenUrl() == null || details.tokenUrl().isBlank()) {
            throw new InvalidServiceRequestException("Token URL is required");
        }
        if (details.clientId() == null || details.clientId().isBlank()) {
            throw new InvalidServiceRequestException("Client id is required");
        }
        if (operation.secret() == null || operation.secret().isBlank()) {
            throw new InvalidServiceRequestException("Client secret is required");
        }
        String key = details.tokenUrl() + "\n" + details.clientId() + "\n"
                + String.join(" ", details.scopes()) + "\n" + operation.secret();
        Instant now = clock.instant();
        CachedToken cached = cache.get(key);
        if (cached != null && now.isBefore(cached.usableUntil())) {
            return cached.token();
        }
        TransportResponse response = transport.send(tokenRequest(operation, details));
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("OAuth token request failed");
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body() == null ? "" : response.body());
        } catch (IOException exception) {
            throw new IOException("OAuth token request failed", exception);
        }
        String token = body.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IOException("OAuth token request failed");
        }
        int expiresIn = body.path("expires_in").asInt(0);
        // The token is discarded 30 seconds early so a slow call does not use an expired token.
        if (expiresIn > 30) {
            cache.put(key, new CachedToken(token, now.plusSeconds(expiresIn - 30L)));
        }
        return token;
    }

    private static OutboundRequest tokenRequest(PublishedOperation operation, AuthDetails details) {
        StringBuilder form = new StringBuilder();
        append(form, "grant_type", "client_credentials");
        append(form, "client_id", details.clientId());
        append(form, "client_secret", operation.secret());
        if (!details.scopes().isEmpty()) {
            append(form, "scope", String.join(" ", details.scopes()));
        }
        return new OutboundRequest(
                HttpMethod.POST,
                details.tokenUrl(),
                Map.of("Content-Type", "application/x-www-form-urlencoded"),
                form.toString(),
                operation.timeoutMillis());
    }

    private static void append(StringBuilder form, String name, String value) {
        if (!form.isEmpty()) {
            form.append('&');
        }
        form.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private record CachedToken(String token, Instant usableUntil) {
    }
}
