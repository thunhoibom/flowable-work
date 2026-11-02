package io.github.illuseahashmap.serviceregistry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ServiceCallerTest {

    @Test
    void returnsUnavailableWhenTransportFails() {
        HttpTransport transport = request -> {
            throw new java.io.IOException("down");
        };
        var caller = new ServiceCaller(transport);
        var operation = new PublishedOperation(
                "createLead", HttpMethod.GET, "https://crm.example/leads",
                AuthType.NONE, null, null, null, 1000, java.util.List.of(), "HTTP_ERROR", java.util.List.of(), java.util.List.of());
        var outcome = caller.call(operation, java.util.Map.of());
        org.junit.jupiter.api.Assertions.assertEquals("SERVICE_UNAVAILABLE", outcome.bpmnErrorCode());
    }

    @Test
    void classifiesTransportResponseAndSendsOperationMethod() {
        var captured = new AtomicReference<OutboundRequest>();
        HttpTransport transport = request -> {
            captured.set(request);
            return new TransportResponse(201, "{\"id\":\"L1\"}");
        };
        var caller = new ServiceCaller(transport);
        var operation = new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthType.NONE, null, null, null, 1000, java.util.List.of(), "HTTP_ERROR",
                java.util.List.of(),
                java.util.List.of(new OutputParameter("id", ParameterType.STRING, "id")));
        var outcome = caller.call(operation, java.util.Map.of());
        org.junit.jupiter.api.Assertions.assertEquals(HttpMethod.POST, captured.get().method());
        org.junit.jupiter.api.Assertions.assertFalse(outcome.failed());
        org.junit.jupiter.api.Assertions.assertEquals("L1", outcome.variables().get("id"));
    }

    @Test
    void doesNotSendWhenTheUrlStillHasAPlaceholder() {
        var called = new java.util.concurrent.atomic.AtomicBoolean(false);
        HttpTransport transport = request -> {
            called.set(true);
            return new TransportResponse(200, "{}");
        };
        var caller = new ServiceCaller(transport);
        var operation = new PublishedOperation(
                "createLead", HttpMethod.GET, "https://crm.example/${missing}",
                AuthType.NONE, null, null, null, 1000, java.util.List.of(), "HTTP_ERROR",
                java.util.List.of(), java.util.List.of());
        org.junit.jupiter.api.Assertions.assertThrows(InvalidServiceRequestException.class,
                () -> caller.call(operation, java.util.Map.of()));
        org.junit.jupiter.api.Assertions.assertFalse(called.get());
    }

    @Test
    void logsResponseStatusAndBodyWithoutTheOperationSecret() {
        HttpTransport transport = request -> new TransportResponse(201, "{\"id\":\"L1\"}");
        var caller = new ServiceCaller(transport);
        var operation = new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthType.BEARER, null, "s3cret", null, 1000, java.util.List.of(), "HTTP_ERROR", java.util.List.of(), java.util.List.of());
        ListAppender<ILoggingEvent> logs = attachLogs();

        try {
            caller.call(operation, java.util.Map.of());
        } finally {
            detachLogs(logs);
        }

        // The request line is logged too. The response line is the one that carries status and body.
        assertThat(logs.list)
                .filteredOn(event -> event.getFormattedMessage().contains("status="))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage()).contains("createLead");
                    assertThat(event.getFormattedMessage()).contains("201");
                    assertThat(event.getFormattedMessage()).contains("{\"id\":\"L1\"}");
                });
        assertThat(logs.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("s3cret"));
    }

    @Test
    void doesNotLogAQueryApiKey() {
        AtomicReference<OutboundRequest> captured = new AtomicReference<>();
        HttpTransport transport = request -> {
            captured.set(request);
            return new TransportResponse(204, "");
        };
        var operation = new PublishedOperation(
                "createLead", HttpMethod.GET, "https://crm.example/leads",
                AuthMode.OVERRIDE, AuthType.API_KEY, "api_key", "s3cret", null,
                1000, 1000, java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                ContentType.JSON, null, java.util.List.of(), BodyPresence.NONE, ApiKeyLocation.QUERY);
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            new ServiceCaller(transport).call(operation, java.util.Map.of());
        } finally {
            detachLogs(logs);
        }
        assertThat(captured.get().url()).contains("api_key=s3cret");
        assertThat(logs.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("s3cret"));
    }

    @Test
    void walksNextUrlsConcatenatesArraysAndSendsCookiesOnlyOnLaterPages() {
        java.util.List<OutboundRequest> sent = new java.util.ArrayList<>();
        HttpTransport transport = request -> {
            sent.add(request);
            if (sent.size() == 1) {
                return new TransportResponse(
                        200,
                        "{\"items\":[1],\"next\":\"/v1/items?page=2\"}",
                        java.util.Map.of("Content-Type", "application/json"),
                        null,
                        java.util.List.of("session=sekret; Path=/"));
            }
            return new TransportResponse(
                    200,
                    "{\"items\":[2],\"status\":\"done\"}",
                    java.util.Map.of(),
                    null,
                    java.util.List.of());
        };
        var operation = new PublishedOperation(
                "list", HttpMethod.GET, "https://files.example/v1/items",
                AuthType.NONE, null, null, null, 1000, java.util.List.of(), "HTTP_ERROR",
                java.util.List.of(),
                java.util.List.of(
                        new OutputParameter("items", ParameterType.JSON, "items"),
                        new OutputParameter("status", ParameterType.STRING, "status")))
                .withSteps(new StepConfig(
                        PaginationMode.NEXT_URL, "next", null, 10, CallStyle.SYNC, null, 0));
        ListAppender<ILoggingEvent> logs = attachLogs();
        CallOutcome outcome;
        try {
            outcome = new ServiceCaller(transport).call(operation, java.util.Map.of());
        } finally {
            detachLogs(logs);
        }
        org.junit.jupiter.api.Assertions.assertEquals(2, sent.size());
        org.junit.jupiter.api.Assertions.assertNull(sent.get(0).headers().get("Cookie"));
        org.junit.jupiter.api.Assertions.assertEquals("https://files.example/v1/items?page=2", sent.get(1).url());
        org.junit.jupiter.api.Assertions.assertEquals("session=sekret", sent.get(1).headers().get("Cookie"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(1, 2), outcome.variables().get("items"));
        org.junit.jupiter.api.Assertions.assertEquals("done", outcome.variables().get("status"));
        assertThat(logs.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("sekret"));
    }

    @Test
    void writesTheNextTokenIntoTheNamedInputAndStopsAtThePageCap() {
        java.util.List<String> urls = new java.util.ArrayList<>();
        HttpTransport transport = request -> {
            urls.add(request.url());
            int page = urls.size();
            return new TransportResponse(200, "{\"rows\":[" + page + "],\"token\":\"" + (page + 1) + "\"}");
        };
        var operation = new PublishedOperation(
                "list", HttpMethod.GET, "https://files.example/rows",
                AuthType.NONE, null, null, null, 1000, java.util.List.of(), "HTTP_ERROR",
                java.util.List.of(new InputParameter("page", ParameterType.STRING, false, ParameterLocation.QUERY)),
                java.util.List.of(new OutputParameter("rows", ParameterType.JSON, "rows")))
                .withSteps(new StepConfig(
                        PaginationMode.NEXT_TOKEN, "token", "page", 2, CallStyle.SYNC, null, 0));
        var outcome = new ServiceCaller(transport).call(operation, java.util.Map.of());
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("https://files.example/rows", "https://files.example/rows?page=2"), urls);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(1, 2), outcome.variables().get("rows"));
    }

    @Test
    void stopsPagingWhenALaterPageFails() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpTransport transport = request -> {
            if (calls.incrementAndGet() == 1) {
                return new TransportResponse(200, "{\"items\":[1],\"next\":\"https://files.example/next\"}");
            }
            return new TransportResponse(500, "{}");
        };
        var operation = new PublishedOperation(
                "list", HttpMethod.GET, "https://files.example/items",
                AuthType.NONE, null, null, null, 1000, java.util.List.of(), "HTTP_ERROR",
                java.util.List.of(),
                java.util.List.of(new OutputParameter("items", ParameterType.JSON, "items")))
                .withSteps(new StepConfig(
                        PaginationMode.NEXT_URL, "next", null, 10, CallStyle.SYNC, null, 0));
        var outcome = new ServiceCaller(transport).call(operation, java.util.Map.of());
        org.junit.jupiter.api.Assertions.assertEquals("HTTP_ERROR", outcome.bpmnErrorCode());
        org.junit.jupiter.api.Assertions.assertTrue(outcome.variables().isEmpty());
    }

    private ListAppender<ILoggingEvent> attachLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(ServiceCaller.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachLogs(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ServiceCaller.class);
        logger.detachAppender(appender);
    }
}
