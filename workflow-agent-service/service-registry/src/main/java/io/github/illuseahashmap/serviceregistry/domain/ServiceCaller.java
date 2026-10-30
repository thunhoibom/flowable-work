package io.github.illuseahashmap.serviceregistry.domain;

import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServiceCaller {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCaller.class);
    private static final int MAX_LOGGED_BODY_LENGTH = 2000;

    private final HttpTransport transport;

    public ServiceCaller(HttpTransport transport) {
        this.transport = transport;
    }

    public CallOutcome call(PublishedOperation operation, Map<String, Object> resolvedInputs) {
        OutboundRequest request = RequestFactory.create(operation, resolvedInputs);
        LOGGER.info(
                "Service call request operation={} method={} url={} body={}",
                operation.operationKey(),
                request.method(),
                request.url(),
                loggedBody(request.body()));
        try {
            TransportResponse response = transport.send(request);
            LOGGER.info(
                    "Service call response operation={} status={} body={}",
                    operation.operationKey(),
                    response.status(),
                    loggedBody(response.body()));
            return CallClassifier.classify(operation, response.status(), response.body());
        } catch (IOException exception) {
            LOGGER.warn("Service call failed before a response operation={}", operation.operationKey());
            return new CallOutcome("SERVICE_UNAVAILABLE", Map.of());
        }
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
