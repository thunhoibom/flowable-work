package io.github.illuseahashmap.serviceregistry.infrastructure;

import io.github.illuseahashmap.serviceregistry.domain.HttpTransport;
import io.github.illuseahashmap.serviceregistry.domain.OutboundRequest;
import io.github.illuseahashmap.serviceregistry.domain.TransportResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;

    public JdkHttpTransport() {
        this(HttpClient.newHttpClient());
    }

    JdkHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public TransportResponse send(OutboundRequest request) throws IOException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.url()))
                    .timeout(Duration.ofMillis(request.timeoutMillis()));
            request.headers().forEach(builder::header);
            builder.method(request.method().name(), body(request.body()));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new TransportResponse(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Service call was interrupted", exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Service call could not be sent", exception);
        }
    }

    private static HttpRequest.BodyPublisher body(String body) {
        return body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
    }
}
