package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceCatalogTest {

    @Test
    void publishedCallUsesSnapshotUntilNextPublish() {
        var catalog = new InMemoryServiceCatalog();
        catalog.saveDraft(new ServiceDraft("crm", "CRM", List.of(operation("https://old.example"))));
        org.junit.jupiter.api.Assertions.assertTrue(catalog.published("crm", "createLead").isEmpty());
        catalog.publish("crm");
        org.junit.jupiter.api.Assertions.assertEquals("https://old.example",
                catalog.published("crm", "createLead").orElseThrow().urlTemplate());
        catalog.saveDraft(new ServiceDraft("crm", "CRM", List.of(operation("https://new.example"))));
        org.junit.jupiter.api.Assertions.assertEquals("https://old.example",
                catalog.published("crm", "createLead").orElseThrow().urlTemplate());
        catalog.publish("crm");
        org.junit.jupiter.api.Assertions.assertEquals("https://new.example",
                catalog.published("crm", "createLead").orElseThrow().urlTemplate());
    }

    @Test
    void saveOperationReplacesParametersAndKeepsPublishedSnapshot() {
        var catalog = new InMemoryServiceCatalog();
        catalog.saveService("crm", "CRM");
        catalog.saveOperation("crm", operation("https://old.example", List.of(
                new InputParameter("orderId", ParameterType.STRING, true, ParameterLocation.PATH)), List.of(
                new OutputParameter("id", ParameterType.LONG, "id"))));
        catalog.publish("crm");

        assertThrows(IllegalArgumentException.class, () -> catalog.saveOperation("crm", operation(
                "https://old.example",
                List.of(new InputParameter("orderId", ParameterType.STRING, false, ParameterLocation.PATH)),
                List.of())));
        assertEquals(ParameterLocation.PATH, catalog.find("crm").orElseThrow().operations().getFirst()
                .inputParameters().getFirst().location());

        catalog.saveOperation("crm", operation("https://new.example", List.of(
                new InputParameter("name", ParameterType.STRING, false, ParameterLocation.QUERY)), List.of()));
        PublishedOperation published = catalog.published("crm", "createLead").orElseThrow();
        assertEquals("https://old.example", published.urlTemplate());
        assertEquals("orderId", published.inputs().getFirst().name());
        OperationView view = catalog.find("crm").orElseThrow().operations().getFirst();
        assertEquals("name", view.inputParameters().getFirst().name());
        assertEquals("orderId", view.publishedInputParameters().getFirst().name());
        assertEquals(HttpMethod.POST, view.publishedMethod());
        assertEquals("HTTP_ERROR", view.publishedBpmnErrorCode());
    }

    private static PublishedOperation operation(String url) {
        return operation(url, List.of(), List.of());
    }

    private static PublishedOperation operation(String url, List<InputParameter> inputs, List<OutputParameter> outputs) {
        return new PublishedOperation(
                "createLead", HttpMethod.POST, url,
                AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
                inputs, outputs);
    }
}
