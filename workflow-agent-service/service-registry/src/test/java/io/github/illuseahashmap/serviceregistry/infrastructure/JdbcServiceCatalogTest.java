package io.github.illuseahashmap.serviceregistry.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InputParameter;
import io.github.illuseahashmap.serviceregistry.domain.OutputParameter;
import io.github.illuseahashmap.serviceregistry.domain.ParameterLocation;
import io.github.illuseahashmap.serviceregistry.domain.ParameterType;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.ServiceDraft;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcServiceCatalogTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void publishedCallKeepsSnapshotUntilNextPublishAndStoresSecretAsCipher() throws Exception {
        applyMigration();
        var catalog = new JdbcServiceCatalog(dataSource(), new ServiceSecretCipher(KEY));
        catalog.saveDraft(new ServiceDraft("crm", "CRM", List.of(operation("https://old.example", "s3cret"))));
        assertTrue(catalog.published("crm", "createLead").isEmpty());

        catalog.publish("crm");
        PublishedOperation published = catalog.published("crm", "createLead").orElseThrow();
        assertEquals("https://old.example", published.urlTemplate());
        assertEquals("s3cret", published.secret());
        assertFalse(column("secret_cipher").contains("s3cret"));
        assertFalse(column("snapshot_json").contains("s3cret"));

        catalog.saveDraft(new ServiceDraft("crm", "CRM", List.of(operation("https://new.example", "s3cret"))));
        assertEquals("https://old.example", catalog.published("crm", "createLead").orElseThrow().urlTemplate());
        catalog.publish("crm");
        assertEquals("https://new.example", catalog.published("crm", "createLead").orElseThrow().urlTemplate());
    }

    @Test
    void parameterChangeStaysInDraftUntilPublishAndOldSnapshotHasNoParameters() throws Exception {
        applyMigration();
        var catalog = new JdbcServiceCatalog(dataSource(), new ServiceSecretCipher(KEY));
        catalog.saveDraft(new ServiceDraft("legacy", "Legacy", List.of(operation("https://old.example", null))));
        catalog.publish("legacy");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE service_publication
                    SET snapshot_json = replace(snapshot_json, ',"inputParameters":[]', '')
                    WHERE service_key = 'legacy'
                    """);
            statement.executeUpdate("""
                    UPDATE service_publication
                    SET snapshot_json = replace(snapshot_json, ',"outputParameters":[]', '')
                    WHERE service_key = 'legacy'
                    """);
        }
        assertEquals(List.of(), catalog.published("legacy", "createLead").orElseThrow().inputs());

        catalog.saveService("orders", "Orders");
        catalog.saveOperation("orders", operation(
                "https://crm.example/leads",
                "s3cret",
                List.of(new InputParameter("orderId", ParameterType.STRING, true, ParameterLocation.PATH)),
                List.of()));
        catalog.publish("orders");
        assertThrows(IllegalArgumentException.class, () -> catalog.saveOperation("orders", new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthType.BEARER, null, null, null, 1000, List.of("409"), "LEAD_CONFLICT",
                List.of(new InputParameter("orderId", ParameterType.STRING, false, ParameterLocation.PATH)),
                List.of())));
        assertEquals("orderId", catalog.published("orders", "createLead").orElseThrow().inputs().getFirst().name());
        catalog.saveOperation("orders", new PublishedOperation(
                "createLead", HttpMethod.POST, "https://changed.example/leads",
                AuthType.BEARER, null, null, null, 1000, List.of("409"), "LEAD_CONFLICT",
                List.of(new InputParameter("name", ParameterType.STRING, false, ParameterLocation.QUERY)),
                List.of()));
        assertEquals("orderId", catalog.published("orders", "createLead").orElseThrow().inputs().getFirst().name());
        assertEquals("name", catalog.find("orders").orElseThrow().operations().getFirst().inputParameters().getFirst().name());
    }

    private static PublishedOperation operation(String url, String secret) {
        return operation(url, secret, List.of(), List.of());
    }

    private static PublishedOperation operation(
            String url, String secret, List<InputParameter> inputs, List<OutputParameter> outputs) {
        return new PublishedOperation(
                "createLead", HttpMethod.POST, url,
                AuthType.BEARER, null, secret, null, 1000, List.of("409"), "LEAD_CONFLICT",
                inputs, outputs);
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static boolean migrated;

    private static void applyMigration() throws Exception {
        if (migrated) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            executeScript(statement, "/db/migration/platform/V32__add_service_registry.sql");
            executeScript(statement, "/db/migration/platform/V33__add_service_operation_parameters.sql");
            migrated = true;
        }
    }

    private static void executeScript(Statement statement, String resource) throws Exception {
        String sql = new String(JdbcServiceCatalogTest.class.getResourceAsStream(resource).readAllBytes(),
                StandardCharsets.UTF_8);
        for (String command : sql.split(";")) {
            if (!command.isBlank()) {
                statement.execute(command);
            }
        }
    }

    private static String column(String name) throws Exception {
        String sql = "snapshot_json".equals(name)
                ? "SELECT snapshot_json FROM service_publication WHERE service_key = 'crm'"
                : "SELECT secret_cipher FROM service_operation_draft WHERE service_key = 'crm'";
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
