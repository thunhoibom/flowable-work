package io.github.illuseahashmap.serviceregistry.domain;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServiceCaller {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCaller.class);
    private static final int MAX_LOGGED_BODY_LENGTH = 2000;

    private final HttpTransport transport;
    private final OauthClientCredentials oauth;

    public ServiceCaller(HttpTransport transport) {
        this(transport, new OauthClientCredentials(transport, Clock.systemUTC()));
    }

    ServiceCaller(HttpTransport transport, OauthClientCredentials oauth) {
        this.transport = transport;
        this.oauth = oauth;
    }

    public CallOutcome call(PublishedOperation operation, Map<String, Object> resolvedInputs) {
        return call(operation, resolvedInputs, null);
    }

    public CallOutcome call(PublishedOperation operation, Map<String, Object> resolvedInputs, String baseUrl) {
        StepConfig steps = MultiStepCall.steps(operation);
        Map<String, Object> inputs = new LinkedHashMap<>(resolvedInputs == null ? Map.of() : resolvedInputs);
        MultiStepCall.CookieJar cookies = new MultiStepCall.CookieJar();
        Map<String, Object> combined = new LinkedHashMap<>();
        String absoluteUrl = null;
        // NONE stays one request. A page cap includes the first page.
        int limit = steps.pagination() == PaginationMode.NONE ? 1 : steps.maxPages();
        for (int page = 0; page < limit; page++) {
            Map<String, String> extra = cookies.isEmpty() ? Map.of() : Map.of("Cookie", cookies.header());
            OutboundRequest request = RequestFactory.create(operation, inputs, baseUrl, absoluteUrl, extra);
            Sent sent = send(operation, request);
            // A failed page stops the loop. Earlier pages are not returned as a partial success.
            if (sent.outcome().failed()) {
                return sent.outcome();
            }
            MultiStepCall.merge(combined, sent.outcome().variables());
            if (steps.pagination() == PaginationMode.NONE || sent.response() == null) {
                break;
            }
            TransportResponse response = sent.response();
            cookies.absorb(response.setCookies());
            String next = MultiStepCall.nextText(response.body(), steps.nextField());
            if (next == null) {
                break;
            }
            if (steps.pagination() == PaginationMode.NEXT_URL) {
                absoluteUrl = MultiStepCall.resolveNextUrl(request.url(), next);
            } else {
                absoluteUrl = null;
                inputs.put(steps.nextTokenParameter(), token(operation, response.body(), steps));
            }
        }
        return new CallOutcome(null, combined);
    }

    private record Sent(CallOutcome outcome, TransportResponse response) {
    }

    private Sent send(PublishedOperation operation, OutboundRequest request) {
        // Slice 1 stores retry policy but still makes one attempt per page.
        if (operation.authType() == AuthType.OAUTH2_CLIENT) {
            try {
                // The token is not logged. Authorization is added after the request is built.
                request.headers().put("Authorization", "Bearer " + oauth.accessToken(operation));
            } catch (InvalidServiceRequestException exception) {
                throw exception;
            } catch (IOException exception) {
                LOGGER.warn("Service call failed before a response operation={}", operation.operationKey());
                return new Sent(new CallOutcome("SERVICE_UNAVAILABLE", Map.of()), null);
            }
        }
        LOGGER.info(
                "Service call request operation={} method={} url={} body={}",
                operation.operationKey(),
                request.method(),
                loggedUrl(operation, request.url()),
                loggedBody(request));
        try {
            TransportResponse response = transport.send(request);
            LOGGER.info(
                    "Service call response operation={} status={} body={}",
                    operation.operationKey(),
                    response.status(),
                    loggedResponse(response));
            return new Sent(CallClassifier.classify(operation, response), response);
        } catch (IOException exception) {
            LOGGER.warn("Service call failed before a response operation={}", operation.operationKey());
            return new Sent(new CallOutcome("SERVICE_UNAVAILABLE", Map.of()), null);
        }
    }

    private static Object token(PublishedOperation operation, String body, StepConfig steps) {
        ParameterType type = operation.inputs().stream()
                .filter(input -> steps.nextTokenParameter().equals(input.name()))
                .map(InputParameter::type)
                .findFirst()
                .orElseThrow(() -> new InvalidServiceRequestException("Next token parameter is required"));
        Object value = MultiStepCall.nextToken(body, steps.nextField(), type);
        if (value == null) {
            throw new InvalidServiceRequestException("Next token is required");
        }
        return value;
    }

    /** Query API keys sit in the URL. The log keeps the parameter name and drops the secret. */
    private static String loggedUrl(PublishedOperation operation, String url) {
        if (operation.authType() != AuthType.API_KEY
                || operation.apiKeyLocation() != ApiKeyLocation.QUERY
                || operation.secret() == null
                || operation.secret().isBlank()
                || operation.apiKeyHeader() == null) {
            return url;
        }
        String name = URLEncoder.encode(operation.apiKeyHeader(), StandardCharsets.UTF_8);
        String secret = URLEncoder.encode(operation.secret(), StandardCharsets.UTF_8);
        return url.replace(name + "=" + secret, name + "=***");
    }

    private static String loggedBody(OutboundRequest request) {
        if (request.binaryBody() != null) {
            return "[binary " + request.binaryBody().length + " bytes]";
        }
        return loggedBody(request.body());
    }

    private static String loggedResponse(TransportResponse response) {
        if (CallClassifier.binary(CallClassifier.contentType(response.headers())) && response.rawBody() != null) {
            return "[binary " + response.rawBody().length + " bytes]";
        }
        return loggedBody(response.body());
    }

    private static String loggedBody(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= MAX_LOGGED_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_LOGGED_BODY_LENGTH) + "...";
    }
}
