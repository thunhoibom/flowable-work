package io.github.illuseahashmap.serviceregistry.infrastructure;

import io.github.illuseahashmap.serviceregistry.domain.CallClassifier;
import io.github.illuseahashmap.serviceregistry.domain.HttpTransport;
import io.github.illuseahashmap.serviceregistry.domain.OutboundRequest;
import io.github.illuseahashmap.serviceregistry.domain.TransportResponse;
import io.github.illuseahashmap.serviceregistry.domain.MutualTlsIdentity;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

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
            HttpClient http = request.clientIdentity() == null ? client : clientFor(request.clientIdentity());
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.url()))
                    .timeout(Duration.ofMillis(request.timeoutMillis()));
            request.headers().forEach(builder::header);
            builder.method(request.method().name(), body(request));
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            Map<String, String> headers = headers(response);
            Charset charset = CallClassifier.charset(CallClassifier.contentType(headers));
            return new TransportResponse(
                    response.statusCode(), new String(bytes, charset), headers, bytes, setCookies(response));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Service call was interrupted", exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Service call could not be sent", exception);
        }
    }

    private static Map<String, String> headers(HttpResponse<byte[]> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                headers.put(name, String.join(", ", values));
            }
        });
        return headers;
    }

    private static List<String> setCookies(HttpResponse<byte[]> response) {
        List<String> cookies = new ArrayList<>();
        response.headers().map().forEach((name, values) -> {
            if (name != null && name.equalsIgnoreCase("set-cookie") && values != null) {
                values.stream().filter(value -> value != null && !value.isBlank()).forEach(cookies::add);
            }
        });
        return cookies;
    }

    private static HttpClient clientFor(MutualTlsIdentity identity) throws IOException {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            char[] password = "workflow-agent".toCharArray();
            store.setKeyEntry("client", identity.privateKey(), password, identity.chain());
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return HttpClient.newBuilder().sslContext(context).build();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IOException("Service call could not be sent", exception);
        }
    }

    private static HttpRequest.BodyPublisher body(OutboundRequest request) {
        // Multipart file bytes must not pass through a character encoding.
        if (request.binaryBody() != null) {
            return HttpRequest.BodyPublishers.ofByteArray(request.binaryBody());
        }
        return request.body() == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(request.body());
    }
}
