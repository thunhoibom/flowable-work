package io.github.illuseahashmap.serviceregistry.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RequestFactory {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RequestFactory() {
    }

    public static OutboundRequest create(PublishedOperation operation, Map<String, Object> resolvedInputs) {
        return create(operation, resolvedInputs, null);
    }

    public static OutboundRequest create(
            PublishedOperation operation, Map<String, Object> resolvedInputs, String baseUrl) {
        return create(operation, resolvedInputs, baseUrl, null, Map.of());
    }

    /**
     * @param absoluteUrl when set, this is already the next page URL, so path and query inputs are not applied again
     * @param extraHeaders cookies from earlier pages in this run; applied before auth so a signature includes them
     */
    static OutboundRequest create(
            PublishedOperation operation,
            Map<String, Object> resolvedInputs,
            String baseUrl,
            String absoluteUrl,
            Map<String, String> extraHeaders) {
        Map<String, Object> values = resolvedInputs == null ? Map.of() : resolvedInputs;
        boolean fixedUrl = absoluteUrl != null && !absoluteUrl.isBlank();
        // Join before placeholder replacement so ${id} is encoded inside the final URL.
        String url = fixedUrl ? absoluteUrl : RequestUrl.join(baseUrl, operation.urlTemplate());
        StringBuilder query = new StringBuilder();
        Map<String, String> headers = new LinkedHashMap<>();
        // Static headers go on first. A process header, then auth, can replace the same name.
        applyStaticHeaders(headers, operation);
        if (extraHeaders != null) {
            extraHeaders.forEach((name, value) -> {
                if (name != null && !name.isBlank()) {
                    headers.put(name, value == null ? "" : value);
                }
            });
        }
        ContentType contentType = operation.contentType();
        BodyPresence presence = operation.bodyPresence();
        boolean buildJson = contentType == ContentType.JSON && presence == BodyPresence.CONTENT
                && operation.inputs().stream().anyMatch(input -> input.location() == ParameterLocation.BODY);
        BodyDocument body = buildJson ? readBody(operation.bodyTemplate()) : null;
        for (InputParameter input : operation.inputs()) {
            Object raw = values.get(input.name());
            boolean present = raw != null;
            switch (input.location()) {
                case PATH -> {
                    if (!fixedUrl && present) {
                        url = url.replace("${" + input.name() + "}", encodePath(textual(input.type(), raw)));
                    }
                }
                case QUERY -> {
                    if (!fixedUrl && present) {
                        appendQuery(query, input.name(), textual(input.type(), raw));
                    }
                }
                case HEADER -> {
                    if (present) {
                        headers.put(input.name(), textual(input.type(), raw));
                    }
                }
                case BODY -> {
                    if (!buildJson || body == null) {
                        break;
                    }
                    if (present) {
                        body.set(input.name(), JSON.valueToTree(raw));
                    } else {
                        body.remove(input.name());
                    }
                }
            }
        }
        if (url.contains("${")) {
            throw new InvalidServiceRequestException("URL still contains a placeholder");
        }
        BuiltBody built = buildBody(operation, values, body, contentType, presence);
        if (built.contentType() != null && !hasHeader(headers, "Content-Type")) {
            headers.put("Content-Type", built.contentType());
        }
        // Header auth is last so it replaces a static header of the same name.
        // HMAC and AWS need the final URL and body, so the query is appended first.
        MutualTlsIdentity identity = applyAuth(headers, query, operation, values);
        if (!query.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + query;
        }
        applySignedAuth(headers, operation, url, built);
        return new OutboundRequest(
                operation.method(),
                url,
                headers,
                built.text(),
                operation.timeoutMillis(),
                built.binary(),
                identity);
    }

    private static void applyStaticHeaders(Map<String, String> headers, PublishedOperation operation) {
        for (StaticHeader header : operation.staticHeaders()) {
            if (header == null || header.name() == null || header.name().isBlank()) {
                throw new InvalidServiceRequestException("Static header name is required");
            }
            headers.put(header.name(), header.value() == null ? "" : header.value());
        }
    }

    private static MutualTlsIdentity applyAuth(
            Map<String, String> headers,
            StringBuilder query,
            PublishedOperation operation,
            Map<String, Object> values) {
        AuthType type = operation.authType() == null ? AuthType.NONE : operation.authType();
        return switch (type) {
            case NONE, OAUTH2_CLIENT, HMAC, AWS_SIG_V4 -> null;
            case BEARER -> {
                if (operation.secret() != null) {
                    headers.put("Authorization", "Bearer " + operation.secret());
                }
                yield null;
            }
            case BEARER_VARIABLE -> {
                headers.put("Authorization", "Bearer " + bearerVariable(operation, values));
                yield null;
            }
            case BASIC -> {
                if (operation.secret() != null) {
                    headers.put("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString(operation.secret().getBytes(StandardCharsets.UTF_8)));
                }
                yield null;
            }
            case API_KEY -> {
                if (operation.secret() != null) {
                    applyApiKey(headers, query, operation);
                }
                yield null;
            }
            case MTLS -> MutualTlsIdentity.parse(operation.authDetails().certificatePem(), operation.secret());
        };
    }

    private static void applySignedAuth(
            Map<String, String> headers, PublishedOperation operation, String url, BuiltBody built) {
        AuthType type = operation.authType();
        if (type == AuthType.HMAC) {
            signHmac(headers, operation, url, built);
        } else if (type == AuthType.AWS_SIG_V4) {
            AuthDetails details = operation.authDetails();
            AwsSigV4.sign(
                    operation.method(),
                    url,
                    headers,
                    payloadBytes(built),
                    details.accessKey(),
                    operation.secret(),
                    details.region(),
                    details.awsService(),
                    Instant.now());
        }
    }

    /** Canonical string is method, path with query, and body, separated by newlines. */
    private static void signHmac(
            Map<String, String> headers, PublishedOperation operation, String url, BuiltBody built) {
        String header = operation.authDetails().signatureHeader();
        if (header == null || header.isBlank()) {
            throw new InvalidServiceRequestException("Signature header is required");
        }
        if (operation.secret() == null || operation.secret().isBlank()) {
            throw new InvalidServiceRequestException("HMAC secret is required");
        }
        URI uri = URI.create(url);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery();
        String target = query == null || query.isEmpty() ? path : path + "?" + query;
        String payload = payloadText(built);
        String canonical = operation.method().name() + "\n" + target + "\n" + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(operation.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            headers.put(header, HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is required", exception);
        }
    }

    private static String bearerVariable(PublishedOperation operation, Map<String, Object> values) {
        String name = operation.authDetails().bearerVariable();
        if (name == null || name.isBlank()) {
            throw new InvalidServiceRequestException("Bearer variable is required");
        }
        Object value = values.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new InvalidServiceRequestException("Bearer variable is required");
        }
        return String.valueOf(value);
    }

    private static byte[] payloadBytes(BuiltBody built) {
        if (built.binary() != null) {
            return built.binary();
        }
        if (built.text() == null) {
            return new byte[0];
        }
        return built.text().getBytes(StandardCharsets.UTF_8);
    }

    private static String payloadText(BuiltBody built) {
        if (built.binary() != null) {
            // ISO-8859-1 keeps each file byte in the signed string.
            return new String(built.binary(), StandardCharsets.ISO_8859_1);
        }
        return built.text() == null ? "" : built.text();
    }

    private static void applyApiKey(Map<String, String> headers, StringBuilder query, PublishedOperation operation) {
        if (operation.apiKeyHeader() == null || operation.apiKeyHeader().isBlank()) {
            throw new InvalidServiceRequestException("API key name is required");
        }
        if (operation.apiKeyLocation() == ApiKeyLocation.QUERY) {
            appendQuery(query, operation.apiKeyHeader(), operation.secret());
            return;
        }
        headers.put(operation.apiKeyHeader(), operation.secret());
    }

    private static BuiltBody buildBody(
            PublishedOperation operation,
            Map<String, Object> values,
            BodyDocument jsonBody,
            ContentType contentType,
            BodyPresence presence) {
        if (presence == BodyPresence.NONE) {
            return BuiltBody.none();
        }
        if (presence == BodyPresence.EMPTY) {
            return new BuiltBody("", null, defaultMediaType(operation, contentType));
        }
        return switch (contentType) {
            case JSON -> jsonContent(operation, jsonBody);
            case FORM -> new BuiltBody(formBody(operation, values), null, "application/x-www-form-urlencoded");
            case MULTIPART -> multipartBody(operation, values);
            case RAW -> rawBody(operation);
        };
    }

    private static BuiltBody jsonContent(PublishedOperation operation, BodyDocument jsonBody) {
        boolean hasBody = operation.inputs().stream().anyMatch(input -> input.location() == ParameterLocation.BODY);
        String payload = hasBody ? writeBody(jsonBody) : operation.bodyTemplate();
        String mediaType = payload != null && !payload.isBlank() ? "application/json" : null;
        return new BuiltBody(payload, null, mediaType);
    }

    private static String formBody(PublishedOperation operation, Map<String, Object> values) {
        StringBuilder form = new StringBuilder();
        for (InputParameter input : operation.inputs()) {
            if (input.location() != ParameterLocation.BODY || values.get(input.name()) == null) {
                continue;
            }
            appendQuery(form, input.name(), textual(input.type(), values.get(input.name())));
        }
        return form.toString();
    }

    private static BuiltBody rawBody(PublishedOperation operation) {
        boolean hasBodyParameter = operation.inputs().stream()
                .anyMatch(input -> input.location() == ParameterLocation.BODY);
        if (hasBodyParameter) {
            throw new InvalidServiceRequestException("RAW content does not accept body parameters");
        }
        String mediaType = operation.rawMediaType() == null || operation.rawMediaType().isBlank()
                ? "application/octet-stream"
                : operation.rawMediaType();
        String template = operation.bodyTemplate() == null ? "" : operation.bodyTemplate();
        return new BuiltBody(template, null, mediaType);
    }

    private static BuiltBody multipartBody(PublishedOperation operation, Map<String, Object> values) {
        String boundary = "workflow-agent-" + Integer.toHexString(operation.operationKey().hashCode());
        byte[] payload = multipartBytes(operation, values, boundary);
        return new BuiltBody(null, payload, "multipart/form-data; boundary=" + boundary);
    }

    private static byte[] multipartBytes(PublishedOperation operation, Map<String, Object> values, String boundary) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (InputParameter input : operation.inputs()) {
                if (input.location() != ParameterLocation.BODY || values.get(input.name()) == null) {
                    continue;
                }
                Object raw = values.get(input.name());
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                if (input.partKind() == PartKind.FILE) {
                    FilePart file = filePart(input.name(), raw);
                    String disposition = "Content-Disposition: form-data; name=\"" + input.name()
                            + "\"; filename=\"" + file.filename() + "\"\r\n";
                    out.write(disposition.getBytes(StandardCharsets.UTF_8));
                    out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.write(file.bytes());
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write(("Content-Disposition: form-data; name=\"" + input.name() + "\"\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.write(textual(input.type(), raw).getBytes(StandardCharsets.UTF_8));
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (java.io.IOException exception) {
            throw new InvalidServiceRequestException("multipart body cannot be written");
        }
    }

    private static FilePart filePart(String name, Object raw) {
        if (raw instanceof byte[] bytes) {
            return new FilePart(name, bytes);
        }
        if (raw instanceof Map<?, ?> map) {
            String filename = map.get("filename") == null ? name : String.valueOf(map.get("filename"));
            return new FilePart(filename, fileBytes(map.get("content") == null ? map.get("bytes") : map.get("content")));
        }
        if (raw instanceof String text) {
            return new FilePart(name, fileBytes(text));
        }
        throw new InvalidServiceRequestException("file parameter is not bytes");
    }

    private static byte[] fileBytes(Object content) {
        if (content instanceof byte[] bytes) {
            return bytes;
        }
        if (content instanceof String text) {
            try {
                return Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException exception) {
                throw new InvalidServiceRequestException("file parameter is not bytes");
            }
        }
        throw new InvalidServiceRequestException("file parameter is not bytes");
    }

    private static String defaultMediaType(PublishedOperation operation, ContentType contentType) {
        return switch (contentType) {
            case JSON -> "application/json";
            case FORM -> "application/x-www-form-urlencoded";
            case MULTIPART -> "multipart/form-data";
            case RAW -> operation.rawMediaType() == null || operation.rawMediaType().isBlank()
                    ? "application/octet-stream"
                    : operation.rawMediaType();
        };
    }

    private record BuiltBody(String text, byte[] binary, String contentType) {
        private static BuiltBody none() {
            return new BuiltBody(null, null, null);
        }
    }

    private record FilePart(String filename, byte[] bytes) {
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        for (String header : headers.keySet()) {
            if (header.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String textual(ParameterType type, Object value) {
        return switch (type) {
            case STRING -> (String) value;
            case BOOLEAN -> Boolean.TRUE.equals(value) ? "true" : "false";
            case INTEGER, LONG -> Long.toString(((Number) value).longValue());
            case DOUBLE -> Double.toString(((Number) value).doubleValue());
            case JSON -> compact(value);
        };
    }

    private static BodyDocument readBody(String template) {
        String document = template == null || template.isBlank() ? "{}" : template;
        try {
            JsonNode node = JSON.readTree(document);
            if (node instanceof ObjectNode object) {
                return new BodyDocument(object, null);
            }
            if (node instanceof ArrayNode array && !array.isEmpty()) {
                for (JsonNode item : array) {
                    if (!(item instanceof ObjectNode)) {
                        throw new InvalidServiceRequestException("body template is not a JSON object");
                    }
                }
                return new BodyDocument(null, array);
            }
        } catch (JsonProcessingException exception) {
            throw new InvalidServiceRequestException("body template is not a JSON object");
        }
        throw new InvalidServiceRequestException("body template is not a JSON object");
    }

    private static String writeBody(BodyDocument body) {
        try {
            return JSON.writeValueAsString(body.root());
        } catch (JsonProcessingException exception) {
            throw new InvalidServiceRequestException("body template is not a JSON object");
        }
    }

    private static final class BodyDocument {
        private final ObjectNode object;
        private final ArrayNode array;

        private BodyDocument(ObjectNode object, ArrayNode array) {
            this.object = object;
            this.array = array;
        }

        private void set(String name, JsonNode value) {
            for (ObjectNode item : objects()) {
                item.set(name, value.deepCopy());
            }
        }

        private void remove(String name) {
            for (ObjectNode item : objects()) {
                item.remove(name);
            }
        }

        private JsonNode root() {
            return array == null ? object : array;
        }

        private List<ObjectNode> objects() {
            if (array == null) {
                return List.of(object);
            }
            List<ObjectNode> items = new ArrayList<>();
            for (JsonNode item : array) {
                items.add((ObjectNode) item);
            }
            return items;
        }
    }

    private static String compact(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new InvalidServiceRequestException("parameter value cannot be written");
        }
    }

    private static void appendQuery(StringBuilder query, String name, String value) {
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(encodeQuery(name)).append('=').append(encodeQuery(value));
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
