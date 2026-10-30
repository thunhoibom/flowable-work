package io.github.illuseahashmap.serviceregistry.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InputParameter;
import io.github.illuseahashmap.serviceregistry.domain.OperationView;
import io.github.illuseahashmap.serviceregistry.domain.OutputParameter;
import io.github.illuseahashmap.serviceregistry.domain.ParameterContracts;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.domain.ServiceDraft;
import io.github.illuseahashmap.serviceregistry.domain.ServiceView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcServiceCatalog implements ServiceCatalog {

    private static final TypeReference<List<StoredOperation>> SNAPSHOT = new TypeReference<>() {
    };
    private static final TypeReference<List<InputParameter>> INPUTS = new TypeReference<>() {
    };
    private static final TypeReference<List<OutputParameter>> OUTPUTS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ServiceSecretCipher cipher;
    private final ObjectMapper objectMapper;

    public JdbcServiceCatalog(DataSource dataSource, ServiceSecretCipher cipher) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.cipher = cipher;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void saveService(String serviceKey, String name) {
        jdbc.update("""
                INSERT INTO service_definition (service_key, name)
                VALUES (?, ?)
                ON CONFLICT (service_key) DO UPDATE SET name = EXCLUDED.name
                """, serviceKey, name);
    }

    @Override
    public void saveOperation(String serviceKey, PublishedOperation operation) {
        ParameterContracts.check(operation.inputs(), operation.outputs());
        Integer definitions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_definition WHERE service_key = ?",
                Integer.class,
                serviceKey);
        if (definitions == null || definitions == 0) {
            throw new IllegalArgumentException("Unknown service");
        }
        jdbc.update("""
                INSERT INTO service_operation_draft (
                    service_key, operation_key, method, url_template, auth_type, api_key_header,
                    secret_cipher, body_template, timeout_millis, error_status_codes, bpmn_error_code,
                    input_parameters_json, output_parameters_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (service_key, operation_key) DO UPDATE SET
                    method = EXCLUDED.method,
                    url_template = EXCLUDED.url_template,
                    auth_type = EXCLUDED.auth_type,
                    api_key_header = EXCLUDED.api_key_header,
                    secret_cipher = COALESCE(EXCLUDED.secret_cipher, service_operation_draft.secret_cipher),
                    body_template = EXCLUDED.body_template,
                    timeout_millis = EXCLUDED.timeout_millis,
                    error_status_codes = EXCLUDED.error_status_codes,
                    bpmn_error_code = EXCLUDED.bpmn_error_code,
                    input_parameters_json = EXCLUDED.input_parameters_json,
                    output_parameters_json = EXCLUDED.output_parameters_json
                """,
                serviceKey,
                operation.operationKey(),
                operation.method().name(),
                operation.urlTemplate(),
                operation.authType().name(),
                operation.apiKeyHeader(),
                encrypt(operation.secret()),
                operation.bodyTemplate(),
                operation.timeoutMillis(),
                String.join(",", operation.errorStatusCodes()),
                operation.bpmnErrorCode(),
                writeJson(operation.inputs()),
                writeJson(operation.outputs()));
    }

    @Override
    public void saveDraft(ServiceDraft draft) {
        for (PublishedOperation operation : draft.operations()) {
            ParameterContracts.check(operation.inputs(), operation.outputs());
        }
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO service_definition (service_key, name)
                    VALUES (?, ?)
                    ON CONFLICT (service_key) DO UPDATE SET name = EXCLUDED.name
                    """, draft.serviceKey(), draft.name());
            jdbc.update("DELETE FROM service_operation_draft WHERE service_key = ?", draft.serviceKey());
            for (PublishedOperation operation : draft.operations()) {
                jdbc.update("""
                        INSERT INTO service_operation_draft (
                            service_key, operation_key, method, url_template, auth_type, api_key_header,
                            secret_cipher, body_template, timeout_millis, error_status_codes, bpmn_error_code,
                            input_parameters_json, output_parameters_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        draft.serviceKey(),
                        operation.operationKey(),
                        operation.method().name(),
                        operation.urlTemplate(),
                        operation.authType().name(),
                        operation.apiKeyHeader(),
                        encrypt(operation.secret()),
                        operation.bodyTemplate(),
                        operation.timeoutMillis(),
                        String.join(",", operation.errorStatusCodes()),
                        operation.bpmnErrorCode(),
                        writeJson(operation.inputs()),
                        writeJson(operation.outputs()));
            }
        });
    }

    @Override
    public void deleteOperation(String serviceKey, String operationKey) {
        Integer definitions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_definition WHERE service_key = ?",
                Integer.class,
                serviceKey);
        if (definitions == null || definitions == 0) {
            throw new IllegalArgumentException("Unknown service");
        }
        int removed = jdbc.update(
                "DELETE FROM service_operation_draft WHERE service_key = ? AND operation_key = ?",
                serviceKey,
                operationKey);
        if (removed == 0) {
            throw new IllegalArgumentException("Unknown operation");
        }
    }

    @Override
    public void deleteService(String serviceKey) {
        transactions.executeWithoutResult(status -> {
            Integer definitions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM service_definition WHERE service_key = ?",
                    Integer.class,
                    serviceKey);
            if (definitions == null || definitions == 0) {
                throw new IllegalArgumentException("Unknown service");
            }
            jdbc.update("DELETE FROM service_operation_draft WHERE service_key = ?", serviceKey);
            jdbc.update("DELETE FROM service_publication WHERE service_key = ?", serviceKey);
            jdbc.update("DELETE FROM service_definition WHERE service_key = ?", serviceKey);
        });
    }

    @Override
    public void publish(String serviceKey) {
        transactions.executeWithoutResult(status -> {
            Integer definitions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM service_definition WHERE service_key = ?",
                    Integer.class,
                    serviceKey);
            if (definitions == null || definitions == 0) {
                return;
            }
            List<StoredOperation> stored = readDraft(serviceKey);
            jdbc.update("""
                    INSERT INTO service_publication (service_key, snapshot_json, published_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (service_key) DO UPDATE
                        SET snapshot_json = EXCLUDED.snapshot_json, published_at = CURRENT_TIMESTAMP
                    """, serviceKey, writeSnapshot(stored));
        });
    }

    @Override
    public Optional<PublishedOperation> published(String serviceKey, String operationKey) {
        List<String> snapshots = jdbc.query(
                "SELECT snapshot_json FROM service_publication WHERE service_key = ?",
                (resultSet, row) -> resultSet.getString(1),
                serviceKey);
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        for (StoredOperation stored : readSnapshot(snapshots.getFirst())) {
            if (operationKey.equals(stored.operationKey())) {
                return Optional.of(toOperation(stored, decrypt(stored.secretCipher())));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<ServiceView> find(String serviceKey) {
        List<String> names = jdbc.query(
                "SELECT name FROM service_definition WHERE service_key = ?",
                (resultSet, row) -> resultSet.getString(1),
                serviceKey);
        if (names.isEmpty()) {
            return Optional.empty();
        }
        Map<String, StoredOperation> published = new HashMap<>();
        List<String> snapshots = jdbc.query(
                "SELECT snapshot_json FROM service_publication WHERE service_key = ?",
                (resultSet, row) -> resultSet.getString(1),
                serviceKey);
        if (!snapshots.isEmpty()) {
            for (StoredOperation stored : readSnapshot(snapshots.getFirst())) {
                published.put(stored.operationKey(), stored);
            }
        }
        List<OperationView> operations = readDraft(serviceKey).stream()
                .map(stored -> {
                    StoredOperation snapshot = published.get(stored.operationKey());
                    return new OperationView(
                            stored.operationKey(),
                            HttpMethod.valueOf(stored.method()),
                            stored.urlTemplate(),
                            AuthType.valueOf(stored.authType()),
                            stored.apiKeyHeader(),
                            stored.secretCipher() != null,
                            stored.bodyTemplate(),
                            stored.timeoutMillis(),
                            stored.errorStatusCodes(),
                            stored.bpmnErrorCode(),
                            snapshot == null ? null : snapshot.urlTemplate(),
                            snapshot == null ? null : HttpMethod.valueOf(snapshot.method()),
                            snapshot == null ? null : snapshot.bpmnErrorCode(),
                            emptyIfNull(stored.inputParameters()),
                            emptyIfNull(stored.outputParameters()),
                            snapshot == null ? List.of() : emptyIfNull(snapshot.inputParameters()),
                            snapshot == null ? List.of() : emptyIfNull(snapshot.outputParameters()));
                })
                .toList();
        return Optional.of(new ServiceView(serviceKey, names.getFirst(), operations));
    }

    @Override
    public List<ServiceView> list() {
        return jdbc.query(
                "SELECT service_key FROM service_definition ORDER BY service_key",
                (resultSet, row) -> resultSet.getString(1)).stream()
                .map(this::find)
                .flatMap(Optional::stream)
                .toList();
    }

    private List<StoredOperation> readDraft(String serviceKey) {
        return jdbc.query("""
                SELECT operation_key, method, url_template, auth_type, api_key_header, secret_cipher,
                       body_template, timeout_millis, error_status_codes, bpmn_error_code,
                       input_parameters_json, output_parameters_json
                FROM service_operation_draft
                WHERE service_key = ?
                """, (resultSet, row) -> new StoredOperation(
                resultSet.getString("operation_key"),
                resultSet.getString("method"),
                resultSet.getString("url_template"),
                resultSet.getString("auth_type"),
                resultSet.getString("api_key_header"),
                resultSet.getString("secret_cipher"),
                resultSet.getString("body_template"),
                resultSet.getInt("timeout_millis"),
                splitCodes(resultSet.getString("error_status_codes")),
                resultSet.getString("bpmn_error_code"),
                readJson(resultSet.getString("input_parameters_json"), INPUTS),
                readJson(resultSet.getString("output_parameters_json"), OUTPUTS)), serviceKey);
    }

    private String encrypt(String secret) {
        return secret == null ? null : cipher.encrypt(secret);
    }

    private String decrypt(String secretCipher) {
        return secretCipher == null ? null : cipher.decrypt(secretCipher);
    }

    private String writeSnapshot(List<StoredOperation> stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to store published service snapshot", exception);
        }
    }

    private List<StoredOperation> readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, SNAPSHOT);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read published service snapshot", exception);
        }
    }

    private static PublishedOperation toOperation(StoredOperation stored, String secret) {
        return new PublishedOperation(
                stored.operationKey(),
                HttpMethod.valueOf(stored.method()),
                stored.urlTemplate(),
                AuthType.valueOf(stored.authType()),
                stored.apiKeyHeader(),
                secret,
                stored.bodyTemplate(),
                stored.timeoutMillis(),
                stored.errorStatusCodes(),
                stored.bpmnErrorCode(),
                emptyIfNull(stored.inputParameters()),
                emptyIfNull(stored.outputParameters()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to store operation parameters", exception);
        }
    }

    private <T> List<T> readJson(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> values = objectMapper.readValue(json, type);
            return values == null ? List.of() : List.copyOf(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read operation parameters", exception);
        }
    }

    private static <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> splitCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.copyOf(List.of(raw.split(",")));
    }

    public record StoredOperation(
            String operationKey,
            String method,
            String urlTemplate,
            String authType,
            String apiKeyHeader,
            String secretCipher,
            String bodyTemplate,
            int timeoutMillis,
            List<String> errorStatusCodes,
            String bpmnErrorCode,
            List<InputParameter> inputParameters,
            List<OutputParameter> outputParameters
    ) {
    }
}
