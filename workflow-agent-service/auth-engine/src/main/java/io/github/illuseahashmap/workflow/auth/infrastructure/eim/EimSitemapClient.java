package io.github.illuseahashmap.workflow.auth.infrastructure.eim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls {@code GET {realm}/auth/sitemaps-for-role-prl} the same way rsa-core-service does: the
 * caller's raw access token (no {@code Bearer} prefix), its {@code transaction_id} as
 * {@code TRANS_ID} and the application code as {@code APP_CODE}.
 */
class EimSitemapClient {

    static final String TRANS_ID_HEADER = "TRANS_ID";
    static final String APP_CODE_HEADER = "APP_CODE";
    private static final String ACTIVE = "1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final String sitemapUri;
    private final String appCode;

    EimSitemapClient(RestClient restClient, String sitemapUri, String appCode) {
        this.restClient = restClient;
        this.sitemapUri = sitemapUri;
        this.appCode = appCode;
    }

    List<SitemapNode> fetch(String accessToken, String transactionId) {
        String body;
        try {
            body = restClient.get()
                    .uri(sitemapUri)
                    .header(TRANS_ID_HEADER, transactionId)
                    .header(APP_CODE_HEADER, appCode)
                    .header(HttpHeaders.AUTHORIZATION, accessToken)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) {
                            throw new EimFetchException(status >= 500 ? Outcome.SERVER_ERROR : Outcome.REJECTED,
                                    "EIM sitemap returned HTTP " + status);
                        }
                        return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    });
        } catch (EimFetchException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new EimFetchException(Outcome.UNREACHABLE, "EIM sitemap request failed: " + exception.getMessage());
        }
        return parse(body);
    }

    static List<SitemapNode> parse(String body) {
        JsonNode root;
        try {
            root = body == null ? null : JSON.readTree(body);
        } catch (IOException exception) {
            throw new EimFetchException(Outcome.INVALID_RESPONSE, "EIM sitemap response is not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new EimFetchException(Outcome.INVALID_RESPONSE, "EIM sitemap response is not a JSON object");
        }
        if (!ACTIVE.equals(text(root.get("status")))) {
            throw new EimFetchException(Outcome.REJECTED,
                    "EIM sitemap status is " + text(root.get("status")));
        }
        return nodes(root.get("sitemaps"));
    }

    private static List<SitemapNode> nodes(JsonNode array) {
        if (array == null || array.isNull()) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new EimFetchException(Outcome.INVALID_RESPONSE, "EIM sitemap nodes are not an array");
        }
        List<SitemapNode> nodes = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (!item.isObject()) {
                throw new EimFetchException(Outcome.INVALID_RESPONSE, "EIM sitemap node is not an object");
            }
            JsonNode order = item.get("ord");
            nodes.add(new SitemapNode(
                    text(item.get("strCode")),
                    text(item.get("strName")),
                    text(item.get("strUrl")),
                    order != null && order.canConvertToInt() ? order.asInt() : Integer.MAX_VALUE,
                    ACTIVE.equals(text(item.get("strStatus"))),
                    privileges(item.get("privileges")),
                    // "childrent" is EIM's own spelling.
                    nodes(item.get("childrent"))));
        }
        return nodes;
    }

    private static List<String> privileges(JsonNode array) {
        if (array == null || array.isNull()) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new EimFetchException(Outcome.INVALID_RESPONSE, "EIM sitemap privileges are not an array");
        }
        List<String> codes = new ArrayList<>(array.size());
        for (JsonNode privilege : array) {
            String code = text(privilege.get("code"));
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** Failure categories, also used as the {@code outcome} metric tag. */
    enum Outcome {
        SUCCESS("success"),
        MISSING_TRANSACTION_ID("missing_transaction_id"),
        UNREACHABLE("unreachable"),
        SERVER_ERROR("server_error"),
        REJECTED("rejected"),
        INVALID_RESPONSE("invalid_response");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    static final class EimFetchException extends RuntimeException {

        private final Outcome outcome;

        EimFetchException(Outcome outcome, String message) {
            super(message, null, false, false);
            this.outcome = outcome;
        }

        Outcome outcome() {
            return outcome;
        }
    }
}
