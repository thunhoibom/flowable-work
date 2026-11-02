package io.github.illuseahashmap.serviceregistry.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.serviceregistry.domain.ApiKeyLocation;
import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthDetails;
import io.github.illuseahashmap.serviceregistry.domain.BodyPresence;
import io.github.illuseahashmap.serviceregistry.domain.ContentType;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.ErrorRule;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InputParameter;
import io.github.illuseahashmap.serviceregistry.domain.OperationView;
import io.github.illuseahashmap.serviceregistry.domain.OutputParameter;
import io.github.illuseahashmap.serviceregistry.domain.ParameterContracts;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.domain.ServiceDraft;
import io.github.illuseahashmap.serviceregistry.domain.ServiceView;
import io.github.illuseahashmap.serviceregistry.domain.StepConfig;
import io.github.illuseahashmap.serviceregistry.domain.StaticHeader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcServiceCatalog implements ServiceCatalog {

    /** Stored for a later slice. This slice still calls the service once and does not read the policy back. */
    private static final String STORED_CALL_POLICY =
            "{\"maxAttempts\":1,\"retryStatuses\":[],\"backoffMillis\":0,\"followRedirects\":true,\"ignoreErrors\":false}";

    private static final TypeReference<List<StoredOperation>> OPERATION_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<InputParameter>> INPUTS = new TypeReference<>() {
    };
    private static final TypeReference<List<OutputParameter>> OUTPUTS = new TypeReference<>() {
    };
    private static final TypeReference<List<ErrorRule>> RULES = new TypeReference<>() {
    };
    private static final TypeReference<List<EnvironmentBaseUrl>> BASE_URLS = new TypeReference<>() {
    };
    private static final TypeReference<List<StaticHeader>> STATIC_HEADERS = new TypeReference<>() {
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
        // Only the name changes. Description, base URLs, and shared auth stay as they are.
        jdbc.update("""
                INSERT INTO service_definition (service_key, name)
                VALUES (?, ?)
                ON CONFLICT (service_key) DO UPDATE SET name = EXCLUDED.name
                """, serviceKey, name);
    }

    @Override
    public void saveServiceSettings(
            String serviceKey,
            String name,
            String description,
            List<EnvironmentBaseUrl> baseUrls,
            AuthConfig sharedAuth) {
        int updated = jdbc.update("""
                UPDATE service_definition
                SET name = ?, description = ?, base_urls_json = ?, shared_auth_json = ?
                WHERE service_key = ?
                """,
                name,
                description == null ? "" : description,
                writeJson(baseUrls == null ? List.of() : baseUrls),
                writeJson(storedAuth(sharedAuth)),
                serviceKey);
        if (updated == 0) {
            throw new IllegalArgumentException("Unknown service");
        }
    }

    @Override
    public void saveOperation(String serviceKey, PublishedOperation operation) {
        ParameterContracts.check(operation);
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
                    input_parameters_json, output_parameters_json, auth_mode, connect_timeout_millis,
                    error_rules_json, success_statuses, call_policy_json, content_type, raw_media_type,
                    static_headers_json, body_presence, api_key_location, auth_details_json, step_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    output_parameters_json = EXCLUDED.output_parameters_json,
                    auth_mode = EXCLUDED.auth_mode,
                    connect_timeout_millis = EXCLUDED.connect_timeout_millis,
                    error_rules_json = EXCLUDED.error_rules_json,
                    success_statuses = EXCLUDED.success_statuses,
                    call_policy_json = EXCLUDED.call_policy_json,
                    content_type = EXCLUDED.content_type,
                    raw_media_type = EXCLUDED.raw_media_type,
                    static_headers_json = EXCLUDED.static_headers_json,
                    body_presence = EXCLUDED.body_presence,
                    api_key_location = EXCLUDED.api_key_location,
                    auth_details_json = EXCLUDED.auth_details_json,
                    step_json = EXCLUDED.step_json
                """,
                operationArguments(serviceKey, operation));
    }

    @Override
    public void saveDraft(ServiceDraft draft) {
        for (PublishedOperation operation : draft.operations()) {
            ParameterContracts.check(operation);
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
                            input_parameters_json, output_parameters_json, auth_mode, connect_timeout_millis,
                            error_rules_json, success_statuses, call_policy_json, content_type, raw_media_type,
                            static_headers_json, body_presence, api_key_location, auth_details_json, step_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        operationArguments(draft.serviceKey(), operation));
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
            DraftSettings settings = readSettings(serviceKey);
            if (settings == null) {
                return;
            }
            // The snapshot is an object so base URLs and shared auth travel with the operations.
            StoredPublication publication = new StoredPublication(
                    settings.description(),
                    settings.baseUrls(),
                    settings.sharedAuth(),
                    readDraft(serviceKey));
            jdbc.update("""
                    INSERT INTO service_publication (service_key, snapshot_json, published_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (service_key) DO UPDATE
                        SET snapshot_json = EXCLUDED.snapshot_json, published_at = CURRENT_TIMESTAMP
                    """, serviceKey, writeJson(publication));
        });
    }

    @Override
    public Optional<PublishedOperation> published(String serviceKey, String operationKey) {
        return publishedService(serviceKey)
                .flatMap(service -> service.operations().stream()
                        .filter(operation -> operation.operationKey().equals(operationKey))
                        .findFirst());
    }

    @Override
    public Optional<PublishedService> publishedService(String serviceKey) {
        List<String> snapshots = jdbc.query(
                "SELECT snapshot_json FROM service_publication WHERE service_key = ?",
                (resultSet, row) -> resultSet.getString(1),
                serviceKey);
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        DraftSettings settings = readSettings(serviceKey);
        String name = settings == null ? serviceKey : settings.name();
        return Optional.of(readPublication(serviceKey, name, snapshots.getFirst()));
    }

    @Override
    public Optional<ServiceView> find(String serviceKey) {
        DraftSettings settings = readSettings(serviceKey);
        if (settings == null) {
            return Optional.empty();
        }
        Map<String, PublishedOperation> published = new HashMap<>();
        publishedService(serviceKey).ifPresent(service -> {
            for (PublishedOperation operation : service.operations()) {
                published.put(operation.operationKey(), operation);
            }
        });
        List<OperationView> operations = readDraft(serviceKey).stream()
                .map(stored -> toView(stored, published.get(stored.operationKey())))
                .toList();
        return Optional.of(new ServiceView(
                serviceKey,
                settings.name(),
                settings.description(),
                settings.baseUrls(),
                decrypted(settings.sharedAuth()),
                operations));
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

    private Object[] operationArguments(String serviceKey, PublishedOperation operation) {
        return new Object[] {
                serviceKey,
                operation.operationKey(),
                operation.method().name(),
                operation.urlTemplate(),
                operation.authType().name(),
                operation.apiKeyHeader(),
                encrypt(operation.secret()),
                operation.bodyTemplate(),
                operation.readTimeoutMillis(),
                String.join(",", operation.errorStatusCodes()),
                operation.bpmnErrorCode(),
                writeJson(operation.inputs()),
                writeJson(operation.outputs()),
                operation.authMode().name(),
                operation.connectTimeoutMillis(),
                writeJson(operation.errorRules()),
                String.join(",", operation.successStatuses()),
                STORED_CALL_POLICY,
                operation.contentType().name(),
                operation.rawMediaType() == null ? "" : operation.rawMediaType(),
                writeJson(operation.staticHeaders()),
                operation.bodyPresence().name(),
                operation.apiKeyLocation().name(),
                writeJson(storedDetails(serviceKey, operation)),
                writeJson(operation.steps() == null ? StepConfig.sync() : operation.steps())
        };
    }

    private DraftSettings readSettings(String serviceKey) {
        List<DraftSettings> rows = jdbc.query("""
                SELECT name, description, base_urls_json, shared_auth_json
                FROM service_definition
                WHERE service_key = ?
                """, (resultSet, row) -> new DraftSettings(
                resultSet.getString("name"),
                resultSet.getString("description"),
                readJson(resultSet.getString("base_urls_json"), BASE_URLS),
                readAuth(resultSet.getString("shared_auth_json"))),
                serviceKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<StoredOperation> readDraft(String serviceKey) {
        return jdbc.query("""
                SELECT operation_key, method, url_template, auth_type, api_key_header, secret_cipher,
                       body_template, timeout_millis, error_status_codes, bpmn_error_code,
                       input_parameters_json, output_parameters_json, auth_mode, connect_timeout_millis,
                       error_rules_json, success_statuses, content_type, raw_media_type,
                       static_headers_json, body_presence, api_key_location, auth_details_json, step_json
                FROM service_operation_draft
                WHERE service_key = ?
                """, (resultSet, row) -> {
            List<String> legacyCodes = splitCodes(resultSet.getString("error_status_codes"));
            String legacyCode = resultSet.getString("bpmn_error_code");
            List<ErrorRule> rules = readJson(resultSet.getString("error_rules_json"), RULES);
            // Rows saved before error rules existed keep one rule from the old columns.
            if (rules.isEmpty()) {
                rules = List.of(new ErrorRule(legacyCodes, legacyCode));
            }
            return new StoredOperation(
                    resultSet.getString("operation_key"),
                    resultSet.getString("method"),
                    resultSet.getString("url_template"),
                    resultSet.getString("auth_type"),
                    resultSet.getString("api_key_header"),
                    resultSet.getString("secret_cipher"),
                    resultSet.getString("body_template"),
                    resultSet.getInt("timeout_millis"),
                    legacyCodes,
                    legacyCode,
                    readJson(resultSet.getString("input_parameters_json"), INPUTS),
                    readJson(resultSet.getString("output_parameters_json"), OUTPUTS),
                    resultSet.getString("auth_mode"),
                    resultSet.getInt("connect_timeout_millis"),
                    rules,
                    splitCodes(resultSet.getString("success_statuses")),
                    resultSet.getString("content_type"),
                    resultSet.getString("raw_media_type"),
                    readJson(resultSet.getString("static_headers_json"), STATIC_HEADERS),
                    resultSet.getString("body_presence"),
                    resultSet.getString("api_key_location"),
                    readDetails(resultSet.getString("auth_details_json")),
                    readStep(resultSet.getString("step_json")));
        }, serviceKey);
    }

    private PublishedService readPublication(String serviceKey, String name, String json) {
        try {
            // A leading '[' is a snapshot from before base URLs were stored on the service.
            if (json.stripLeading().startsWith("[")) {
                List<StoredOperation> operations = objectMapper.readValue(json, OPERATION_LIST);
                return new PublishedService(
                        serviceKey,
                        name,
                        "",
                        List.of(),
                        new AuthConfig(AuthType.NONE, null, null),
                        mapOperations(operations));
            }
            StoredPublication stored = objectMapper.readValue(json, StoredPublication.class);
            return new PublishedService(
                    serviceKey,
                    name,
                    stored.description(),
                    stored.baseUrls(),
                    decrypted(stored.sharedAuth()),
                    mapOperations(stored.operations()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read published service snapshot", exception);
        }
    }

    private List<PublishedOperation> mapOperations(List<StoredOperation> stored) {
        if (stored == null) {
            return List.of();
        }
        return stored.stream()
                .map(operation -> toOperation(operation, decrypt(operation.secretCipher())))
                .toList();
    }

    private PublishedOperation toOperation(StoredOperation stored, String secret) {
        List<ErrorRule> rules = stored.errorRules() == null || stored.errorRules().isEmpty()
                ? List.of(new ErrorRule(emptyIfNull(stored.errorStatusCodes()), stored.bpmnErrorCode()))
                : stored.errorRules();
        // Missing connect timeout on an old snapshot means both timeouts were the same value.
        int connect = stored.connectTimeoutMillis() == 0 ? stored.timeoutMillis() : stored.connectTimeoutMillis();
        AuthMode mode = stored.authMode() == null || stored.authMode().isBlank()
                ? AuthMode.OVERRIDE
                : AuthMode.valueOf(stored.authMode());
        AuthType authType = stored.authType() == null ? AuthType.NONE : AuthType.valueOf(stored.authType());
        return new PublishedOperation(
                stored.operationKey(),
                HttpMethod.valueOf(stored.method()),
                stored.urlTemplate(),
                mode,
                authType,
                stored.apiKeyHeader(),
                secret,
                stored.bodyTemplate(),
                connect,
                stored.timeoutMillis(),
                rules,
                emptyIfNull(stored.successStatuses()),
                emptyIfNull(stored.inputParameters()),
                emptyIfNull(stored.outputParameters()),
                enumOr(stored.contentType(), ContentType.JSON, ContentType.class),
                stored.rawMediaType(),
                emptyIfNull(stored.staticHeaders()),
                enumOr(stored.bodyPresence(), BodyPresence.CONTENT, BodyPresence.class),
                enumOr(stored.apiKeyLocation(), ApiKeyLocation.HEADER, ApiKeyLocation.class),
                decryptDetails(stored.authDetails()),
                stored.step() == null ? StepConfig.sync() : stored.step());
    }

    private static <T extends Enum<T>> T enumOr(String raw, T fallback, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Enum.valueOf(type, raw);
    }

    private OperationView toView(StoredOperation stored, PublishedOperation snapshot) {
        PublishedOperation draft = toOperation(stored, null);
        return new OperationView(
                draft.operationKey(),
                draft.method(),
                draft.urlTemplate(),
                draft.authType(),
                draft.apiKeyHeader(),
                stored.secretCipher() != null,
                draft.bodyTemplate(),
                draft.timeoutMillis(),
                draft.errorStatusCodes(),
                draft.bpmnErrorCode(),
                snapshot == null ? null : snapshot.urlTemplate(),
                snapshot == null ? null : snapshot.method(),
                snapshot == null ? null : snapshot.bpmnErrorCode(),
                draft.inputs(),
                draft.outputs(),
                snapshot == null ? List.of() : snapshot.inputs(),
                snapshot == null ? List.of() : snapshot.outputs(),
                draft.authMode(),
                draft.connectTimeoutMillis(),
                draft.errorRules(),
                draft.successStatuses(),
                draft.contentType(),
                draft.rawMediaType(),
                draft.staticHeaders(),
                draft.bodyPresence(),
                draft.apiKeyLocation(),
                draft.authDetails().bearerVariable(),
                draft.authDetails().tokenUrl(),
                draft.authDetails().clientId(),
                draft.authDetails().scopes(),
                present(draft.authDetails().certificatePem()),
                draft.authDetails().signatureHeader(),
                present(draft.authDetails().accessKey()),
                draft.authDetails().region(),
                draft.authDetails().awsService(),
                snapshot == null || snapshot.contentType() == null ? null : snapshot.contentType().name(),
                draft.steps(),
                snapshot == null ? null : snapshot.steps());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private StoredAuth storedAuth(AuthConfig auth) {
        AuthConfig value = auth == null ? new AuthConfig(AuthType.NONE, null, null) : auth;
        AuthType type = value.type() == null ? AuthType.NONE : value.type();
        // The cipher is what gets stored. The plaintext secret never goes into the row or the snapshot.
        AuthDetails details = value.details();
        return new StoredAuth(
                type.name(),
                value.apiKeyHeader(),
                encrypt(value.secret()),
                value.apiKeyLocation().name(),
                details.tokenUrl(),
                details.clientId(),
                details.scopes(),
                encrypt(details.certificatePem()),
                details.bearerVariable(),
                encrypt(details.accessKey()),
                details.region(),
                details.awsService(),
                details.signatureHeader());
    }

    private StoredAuth readAuth(String json) {
        if (json == null || json.isBlank()) {
            return new StoredAuth(AuthType.NONE.name(), null, null, ApiKeyLocation.HEADER.name(),
                    null, null, List.of(), null, null, null, null, null, null);
        }
        try {
            StoredAuth stored = objectMapper.readValue(json, StoredAuth.class);
            return stored == null
                    ? new StoredAuth(AuthType.NONE.name(), null, null, ApiKeyLocation.HEADER.name(),
                            null, null, List.of(), null, null, null, null, null, null)
                    : stored;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read shared service auth", exception);
        }
    }

    private AuthConfig decrypted(StoredAuth stored) {
        if (stored == null || stored.type() == null) {
            return new AuthConfig(AuthType.NONE, null, null);
        }
        return new AuthConfig(
                AuthType.valueOf(stored.type()),
                stored.apiKeyHeader(),
                decrypt(stored.secretCipher()),
                enumOr(stored.apiKeyLocation(), ApiKeyLocation.HEADER, ApiKeyLocation.class),
                new AuthDetails(
                        stored.tokenUrl(),
                        stored.clientId(),
                        stored.scopes(),
                        decrypt(stored.certificateCipher()),
                        stored.bearerVariable(),
                        decrypt(stored.accessKeyCipher()),
                        stored.region(),
                        stored.awsService(),
                        stored.signatureHeader()));
    }

    private StoredAuthDetails storedDetails(String serviceKey, PublishedOperation operation) {
        AuthDetails incoming = operation.authDetails();
        AuthDetails stored = readStoredDetails(serviceKey, operation.operationKey());
        AuthDetails merged = new AuthDetails(
                incoming.tokenUrl(),
                incoming.clientId(),
                incoming.scopes(),
                incoming.certificatePem() == null ? stored.certificatePem() : incoming.certificatePem(),
                incoming.bearerVariable(),
                incoming.accessKey() == null ? stored.accessKey() : incoming.accessKey(),
                incoming.region(),
                incoming.awsService(),
                incoming.signatureHeader());
        return new StoredAuthDetails(
                merged.tokenUrl(),
                merged.clientId(),
                merged.scopes(),
                encrypt(merged.certificatePem()),
                merged.bearerVariable(),
                encrypt(merged.accessKey()),
                merged.region(),
                merged.awsService(),
                merged.signatureHeader());
    }

    private AuthDetails readStoredDetails(String serviceKey, String operationKey) {
        List<String> rows = jdbc.query(
                "SELECT auth_details_json FROM service_operation_draft WHERE service_key = ? AND operation_key = ?",
                (resultSet, row) -> resultSet.getString(1),
                serviceKey,
                operationKey);
        if (rows.isEmpty()) {
            return AuthDetails.empty();
        }
        return decryptDetails(readDetails(rows.getFirst()));
    }

    private AuthDetails decryptDetails(StoredAuthDetails stored) {
        if (stored == null) {
            return AuthDetails.empty();
        }
        return new AuthDetails(
                stored.tokenUrl(),
                stored.clientId(),
                stored.scopes(),
                decrypt(stored.certificateCipher()),
                stored.bearerVariable(),
                decrypt(stored.accessKeyCipher()),
                stored.region(),
                stored.awsService(),
                stored.signatureHeader());
    }

    private StoredAuthDetails readDetails(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, StoredAuthDetails.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read auth details", exception);
        }
    }

    private String encrypt(String secret) {
        return secret == null ? null : cipher.encrypt(secret);
    }

    private String decrypt(String secretCipher) {
        return secretCipher == null ? null : cipher.decrypt(secretCipher);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to store service catalog json", exception);
        }
    }

    private StepConfig readStep(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return StepConfig.sync();
        }
        try {
            StepConfig step = objectMapper.readValue(json, StepConfig.class);
            return step == null ? StepConfig.sync() : step;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read service catalog json", exception);
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
            throw new IllegalStateException("Unable to read service catalog json", exception);
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

    private record DraftSettings(
            String name,
            String description,
            List<EnvironmentBaseUrl> baseUrls,
            StoredAuth sharedAuth
    ) {
    }

    /** Shared auth as stored: every secret field is a cipher, never the plaintext. */
    public record StoredAuth(
            String type,
            String apiKeyHeader,
            String secretCipher,
            String apiKeyLocation,
            String tokenUrl,
            String clientId,
            List<String> scopes,
            String certificateCipher,
            String bearerVariable,
            String accessKeyCipher,
            String region,
            String awsService,
            String signatureHeader) {
    }

    public record StoredAuthDetails(
            String tokenUrl,
            String clientId,
            List<String> scopes,
            String certificateCipher,
            String bearerVariable,
            String accessKeyCipher,
            String region,
            String awsService,
            String signatureHeader) {
    }

    public record StoredPublication(
            String description,
            List<EnvironmentBaseUrl> baseUrls,
            StoredAuth sharedAuth,
            List<StoredOperation> operations
    ) {
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
            List<OutputParameter> outputParameters,
            String authMode,
            int connectTimeoutMillis,
            List<ErrorRule> errorRules,
            List<String> successStatuses,
            String contentType,
            String rawMediaType,
            List<StaticHeader> staticHeaders,
            String bodyPresence,
            String apiKeyLocation,
            StoredAuthDetails authDetails,
            StepConfig step
    ) {
    }
}
