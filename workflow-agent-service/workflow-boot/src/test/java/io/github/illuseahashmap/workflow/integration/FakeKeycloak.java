package io.github.illuseahashmap.workflow.integration;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process stand-in for the Keycloak realm {@code rsa} and its EIM sitemap endpoint: JWK set,
 * password grant (tokens carry {@code eim_roles} and {@code transaction_id}) and
 * {@code sitemaps-for-role-prl}.
 */
final class FakeKeycloak {

    static final String REALM_PATH = "/realms/rsa";
    static final String CLIENT_ID = "VETC.WORKFLOW";

    private final RSAKey signingKey;
    private final HttpServer server;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, String> userByTransaction = new ConcurrentHashMap<>();

    record Account(String password, String roles, String sitemap) {
    }

    FakeKeycloak() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        server.createContext(REALM_PATH + "/protocol/openid-connect/certs",
                exchange -> respond(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toString()));
        server.createContext(REALM_PATH + "/protocol/openid-connect/token", this::token);
        server.createContext(REALM_PATH + "/protocol/openid-connect/logout", exchange -> respond(exchange, 204, ""));
        server.createContext(REALM_PATH + "/auth/sitemaps-for-role-prl", this::sitemap);
        server.start();
    }

    String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** {@code sitemap == null} makes EIM answer HTTP 500 for this user. */
    void account(String username, String password, String roles, String sitemap) {
        accounts.put(username, new Account(password, roles, sitemap));
    }

    String accessToken(String username) {
        Account account = accounts.get(username);
        String transactionId = UUID.randomUUID().toString();
        userByTransaction.put(transactionId, username);
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(serverUrl() + REALM_PATH)
                .subject("sub-" + username)
                .claim("azp", CLIENT_ID)
                .claim("preferred_username", username)
                .claim("name", username)
                .claim("eim_roles", account.roles())
                .claim("transaction_id", transactionId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(1800)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    void stop() {
        server.stop(0);
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Account account = accounts.get(form.get("username"));
        if (!"password".equals(form.get("grant_type")) || !CLIENT_ID.equals(form.get("client_id"))
                || account == null || !account.password().equals(form.get("password"))) {
            respond(exchange, 401, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}");
            return;
        }
        respond(exchange, 200, """
                {"access_token":"%s","expires_in":1800,"refresh_token":"refresh-%s","refresh_expires_in":3600,
                 "token_type":"Bearer"}
                """.formatted(accessToken(form.get("username")), form.get("username")));
    }

    private void sitemap(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String user = userByTransaction.get(String.valueOf(exchange.getRequestHeaders().getFirst("TRANS_ID")));
        Account account = user == null ? null : accounts.get(user);
        if (authorization == null || authorization.startsWith("Bearer ")
                || !CLIENT_ID.equals(exchange.getRequestHeaders().getFirst("APP_CODE")) || account == null) {
            respond(exchange, 401, "{\"status\":\"0\"}");
        } else if (account.sitemap() == null) {
            respond(exchange, 500, "upstream failure");
        } else {
            respond(exchange, 200, account.sitemap());
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                values.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
