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

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("createLead");
            assertThat(event.getFormattedMessage()).contains("201");
            assertThat(event.getFormattedMessage()).contains("{\"id\":\"L1\"}");
            assertThat(event.getFormattedMessage()).doesNotContain("s3cret");
        });
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
