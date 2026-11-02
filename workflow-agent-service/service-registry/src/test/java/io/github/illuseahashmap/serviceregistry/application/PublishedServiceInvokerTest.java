package io.github.illuseahashmap.serviceregistry.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InMemoryServiceCatalog;
import io.github.illuseahashmap.serviceregistry.domain.OutboundRequest;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.domain.TransportResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PublishedServiceInvokerTest {

    @Test
    void callsTheEnvironmentBaseUrlWithTheSharedSecret() {
        var catalog = new InMemoryServiceCatalog();
        catalog.saveService("crm", "CRM");
        catalog.saveServiceSettings("crm", "CRM", "Khách hàng",
                List.of(new EnvironmentBaseUrl("default", "https://crm.example")),
                new AuthConfig(AuthType.BEARER, null, "shared-token"));
        catalog.saveOperation("crm", operation("createLead", "/leads", AuthMode.INHERIT));
        catalog.publish("crm");

        AtomicReference<OutboundRequest> captured = new AtomicReference<>();
        var invoker = invoker(catalog, captured);
        invoker.invoke("crm", "createLead", Map.of());

        assertEquals("https://crm.example/leads", captured.get().url());
        assertEquals("Bearer shared-token", captured.get().headers().get("Authorization"));
    }

    @Test
    void rejectsARelativePathWhenTheEnvironmentHasNoBaseUrl() {
        var catalog = new InMemoryServiceCatalog();
        catalog.saveService("crm", "CRM");
        catalog.saveOperation("crm", operation("createLead", "/leads", AuthMode.INHERIT));
        catalog.publish("crm");

        ServiceCallException error = assertThrows(ServiceCallException.class,
                () -> invoker(catalog, new AtomicReference<>()).invoke("crm", "createLead", Map.of()));
        assertEquals("SERVICE_NOT_PUBLISHED", error.getMessage());
    }

    @Test
    void callsAnAbsoluteUrlWithoutABaseUrl() {
        var catalog = new InMemoryServiceCatalog();
        catalog.saveService("crm", "CRM");
        catalog.saveOperation("crm", operation("ping", "https://other.example/ping", AuthMode.NONE));
        catalog.publish("crm");

        AtomicReference<OutboundRequest> captured = new AtomicReference<>();
        invoker(catalog, captured).invoke("crm", "ping", Map.of());
        assertEquals("https://other.example/ping", captured.get().url());
    }

    private static PublishedServiceInvoker invoker(
            InMemoryServiceCatalog catalog, AtomicReference<OutboundRequest> captured) {
        return new PublishedServiceInvoker(catalog, new ServiceCaller(request -> {
            captured.set(request);
            return new TransportResponse(204, "");
        }), () -> "default");
    }

    private static PublishedOperation operation(String key, String url, AuthMode mode) {
        return new PublishedOperation(
                key, HttpMethod.GET, url,
                mode, AuthType.NONE, null, null, null,
                1000, 1000,
                List.of(), List.of(), List.of(), List.of());
    }
}
