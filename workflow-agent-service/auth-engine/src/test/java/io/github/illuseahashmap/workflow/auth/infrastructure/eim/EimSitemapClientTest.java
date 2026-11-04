package io.github.illuseahashmap.workflow.auth.infrastructure.eim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.github.illuseahashmap.workflow.auth.infrastructure.eim.EimSitemapClient.EimFetchException;
import io.github.illuseahashmap.workflow.auth.infrastructure.eim.EimSitemapClient.Outcome;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class EimSitemapClientTest {

    private static final String PATH = "/realms/rsa/auth/sitemaps-for-role-prl";
    private static final String SITEMAP = """
            {"status":"1","sitemaps":[
              {"strCode":"WF_ROOT","strStatus":"1","privileges":[{"code":"ACCESS"}],"childrent":[
                {"strCode":"WF_DEFINITION","strStatus":"1","privileges":[{"code":"VIEW"},{"code":"UPDATE"}],
                 "childrent":null},
                {"strCode":"WF_SERVICE","strStatus":"0","privileges":[{"code":"ACCESS"}]}
              ]}
            ]}
            """;

    private HttpServer server;
    private final AtomicReference<Headers> received = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = SITEMAP;
    private volatile long delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, exchange -> {
            received.set(exchange.getRequestHeaders());
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsRawTokenTransactionIdAndAppCodeAndParsesTree() {
        List<SitemapNode> nodes = client().fetch("header.payload.signature", "tx-123");

        Headers headers = received.get();
        assertThat(headers.getFirst("Authorization")).isEqualTo("header.payload.signature");
        assertThat(headers.getFirst("TRANS_ID")).isEqualTo("tx-123");
        assertThat(headers.getFirst("APP_CODE")).isEqualTo("VETC.WORKFLOW");

        assertThat(nodes).hasSize(1);
        SitemapNode root = nodes.getFirst();
        assertThat(root.code()).isEqualTo("WF_ROOT");
        assertThat(root.children()).extracting(SitemapNode::code).containsExactly("WF_DEFINITION", "WF_SERVICE");
        assertThat(root.children().get(0).privileges()).containsExactly("VIEW", "UPDATE");
        assertThat(root.children().get(0).children()).isEmpty();
        assertThat(root.children().get(1).active()).isFalse();
    }

    @Test
    void serverErrorIsAnOutage() {
        status = 502;

        assertThatThrownBy(() -> client().fetch("a.b.c", "tx"))
                .isInstanceOfSatisfying(EimFetchException.class,
                        exception -> assertThat(exception.outcome()).isEqualTo(Outcome.SERVER_ERROR));
    }

    @Test
    void clientErrorIsARejection() {
        status = 401;

        assertThatThrownBy(() -> client().fetch("a.b.c", "tx"))
                .isInstanceOfSatisfying(EimFetchException.class,
                        exception -> assertThat(exception.outcome()).isEqualTo(Outcome.REJECTED));
    }

    @Test
    void failedStatusInBodyIsARejection() {
        body = "{\"status\":\"0\",\"message\":\"invalid session\"}";

        assertThatThrownBy(() -> client().fetch("a.b.c", "tx"))
                .isInstanceOfSatisfying(EimFetchException.class,
                        exception -> assertThat(exception.outcome()).isEqualTo(Outcome.REJECTED));
    }

    @Test
    void unreadableBodyIsInvalid() {
        body = "<html>gateway</html>";

        assertThatThrownBy(() -> client().fetch("a.b.c", "tx"))
                .isInstanceOfSatisfying(EimFetchException.class,
                        exception -> assertThat(exception.outcome()).isEqualTo(Outcome.INVALID_RESPONSE));
    }

    @Test
    void readTimeoutIsAnOutage() {
        delayMillis = 1_000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(100));

        assertThatThrownBy(() -> client(factory).fetch("a.b.c", "tx"))
                .isInstanceOfSatisfying(EimFetchException.class,
                        exception -> assertThat(exception.outcome()).isEqualTo(Outcome.UNREACHABLE));
    }

    @Test
    void emptySitemapIsValid() {
        body = "{\"status\":\"1\",\"sitemaps\":[]}";

        assertThat(client().fetch("a.b.c", "tx")).isEmpty();
    }

    private EimSitemapClient client() {
        return client(new SimpleClientHttpRequestFactory());
    }

    private EimSitemapClient client(SimpleClientHttpRequestFactory factory) {
        String uri = "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
        return new EimSitemapClient(RestClient.builder().requestFactory(factory).build(), uri, "VETC.WORKFLOW");
    }
}
