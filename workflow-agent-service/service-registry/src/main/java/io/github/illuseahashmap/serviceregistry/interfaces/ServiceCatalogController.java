package io.github.illuseahashmap.serviceregistry.interfaces;

import io.github.illuseahashmap.serviceregistry.domain.ApiKeyLocation;
import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthDetails;
import io.github.illuseahashmap.serviceregistry.domain.BodyPresence;
import io.github.illuseahashmap.serviceregistry.domain.CallStyle;
import io.github.illuseahashmap.serviceregistry.domain.ContentType;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.ErrorRule;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InputParameter;
import io.github.illuseahashmap.serviceregistry.domain.OutputParameter;
import io.github.illuseahashmap.serviceregistry.domain.OutputSource;
import io.github.illuseahashmap.serviceregistry.domain.ParameterLocation;
import io.github.illuseahashmap.serviceregistry.domain.PartKind;
import io.github.illuseahashmap.serviceregistry.domain.PaginationMode;
import io.github.illuseahashmap.serviceregistry.domain.ParameterType;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.domain.ServiceView;
import io.github.illuseahashmap.serviceregistry.domain.StaticHeader;
import io.github.illuseahashmap.serviceregistry.domain.StepConfig;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/services")
public class ServiceCatalogController {

    private final ServiceCatalog catalog;

    public ServiceCatalogController(ServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ApiResponse<List<ServiceResponse>> list() {
        return ApiResponse.ok(catalog.list().stream().map(ServiceCatalogController::response).toList());
    }

    @PostMapping
    public ApiResponse<Void> create(@RequestBody CreateServiceRequest request) {
        if (request.serviceKey() == null || request.serviceKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service key is required");
        }
        catalog.saveService(request.serviceKey(), request.name());
        return ApiResponse.ok();
    }

    @PutMapping("/{serviceKey}/settings")
    public ApiResponse<Void> saveSettings(@PathVariable String serviceKey,
                                          @RequestBody SaveServiceSettingsRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service name is required");
        }
        List<EnvironmentBaseUrl> baseUrls = request.baseUrls() == null ? List.of() : request.baseUrls();
        for (EnvironmentBaseUrl baseUrl : baseUrls) {
            if (baseUrl.environment() == null || baseUrl.environment().isBlank()
                    || baseUrl.url() == null || baseUrl.url().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base URL is required");
            }
        }
        SharedAuthRequest sharedAuth = request.sharedAuth() == null
                ? new SharedAuthRequest("NONE", null, null, null, null, null, null, null, null, null, null, null, null)
                : request.sharedAuth();
        AuthType type = authType(sharedAuth.type());
        // A missing location keeps the historical header placement.
        ApiKeyLocation keyLocation = enumValue(sharedAuth.apiKeyLocation(), ApiKeyLocation.HEADER, ApiKeyLocation.class);
        if (type == AuthType.API_KEY && (sharedAuth.apiKeyHeader() == null || sharedAuth.apiKeyHeader().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    keyLocation == ApiKeyLocation.QUERY ? "API key name is required" : "API key header is required");
        }
        AuthConfig previous = catalog.find(serviceKey).map(ServiceView::sharedAuth).orElse(null);
        // A blank secret, certificate, or access key keeps the value already stored on the draft.
        String secret = blank(sharedAuth.secret())
                ? previous == null ? null : blankToNull(previous.secret())
                : sharedAuth.secret();
        AuthDetails details = keepStoredSecrets(authDetails(sharedAuth), previous == null ? null : previous.details());
        requireExtendedAuth(type, details);
        try {
            catalog.saveServiceSettings(serviceKey, request.name().trim(),
                    request.description() == null ? "" : request.description().trim(),
                    baseUrls, new AuthConfig(type, sharedAuth.apiKeyHeader(), secret, keyLocation, details));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
        return ApiResponse.ok();
    }

    @PutMapping("/{serviceKey}/operations/{operationKey}")
    public ApiResponse<Void> saveOperation(@PathVariable String serviceKey,
                               @PathVariable String operationKey,
                               @RequestBody SaveOperationRequest request) {
        try {
            catalog.saveOperation(serviceKey, operation(operationKey, request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        return ApiResponse.ok();
    }

    private static PublishedOperation operation(String operationKey, SaveOperationRequest request) {
        List<String> errorStatusCodes = request.errorStatusCodes() == null
                ? List.of()
                : request.errorStatusCodes();
        // Clients that do not send a mode keep the previous OVERRIDE behavior.
        AuthMode mode = request.authMode() == null || request.authMode().isBlank()
                ? AuthMode.OVERRIDE
                : AuthMode.valueOf(request.authMode());
        int connectTimeout = request.connectTimeoutMillis() == null
                ? request.timeoutMillis()
                : request.connectTimeoutMillis();
        List<ErrorRule> rules = request.errorRules() == null
                ? List.of(new ErrorRule(errorStatusCodes, request.bpmnErrorCode()))
                : request.errorRules().stream()
                        .map(rule -> new ErrorRule(rule.patterns(), rule.bpmnErrorCode()))
                        .toList();
        ContentType contentType = enumValue(request.contentType(), ContentType.JSON, ContentType.class);
        BodyPresence bodyPresence = enumValue(request.bodyPresence(), BodyPresence.CONTENT, BodyPresence.class);
        ApiKeyLocation keyLocation = enumValue(request.apiKeyLocation(), ApiKeyLocation.HEADER, ApiKeyLocation.class);
        if (contentType == ContentType.RAW && (request.rawMediaType() == null || request.rawMediaType().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Media type is required");
        }
        List<InputParameter> bodyInputs = inputs(request.inputParameters());
        if (contentType == ContentType.RAW
                && bodyInputs.stream().anyMatch(input -> input.location() == ParameterLocation.BODY)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RAW content does not accept body parameters");
        }
        List<StaticHeader> staticHeaders = staticHeaders(request.staticHeaders());
        AuthType type = AuthType.valueOf(request.authType());
        AuthDetails details = authDetails(request);
        if (mode == AuthMode.OVERRIDE) {
            requireExtendedAuth(type, details);
        }
        List<OutputParameter> outputParameters = outputs(request.outputParameters());
        return new PublishedOperation(
                operationKey,
                HttpMethod.valueOf(request.method()),
                request.url(),
                mode,
                type,
                request.apiKeyHeader(),
                blankToNull(request.secret()),
                request.bodyTemplate(),
                connectTimeout,
                request.timeoutMillis(),
                rules,
                successStatuses(request.successStatuses()),
                bodyInputs,
                outputParameters,
                contentType,
                request.rawMediaType(),
                staticHeaders,
                bodyPresence,
                keyLocation,
                details,
                stepConfig(request.steps()));
    }

    private static StepConfig stepConfig(StepRequest request) {
        if (request == null) {
            return StepConfig.sync();
        }
        return new StepConfig(
                enumValue(request.pagination(), PaginationMode.NONE, PaginationMode.class),
                request.nextField(),
                request.nextTokenParameter(),
                request.maxPages() == null ? 0 : request.maxPages(),
                enumValue(request.callStyle(), CallStyle.SYNC, CallStyle.class),
                request.correlationOutput(),
                request.waitTimeoutMillis() == null ? 0 : request.waitTimeoutMillis());
    }

    private static AuthDetails authDetails(SharedAuthRequest request) {
        return authDetails(
                request.bearerVariable(),
                request.tokenUrl(),
                request.clientId(),
                request.scopes(),
                request.certificate(),
                request.signatureHeader(),
                request.accessKey(),
                request.region(),
                request.awsService());
    }

    private static AuthDetails authDetails(SaveOperationRequest request) {
        return authDetails(
                request.bearerVariable(),
                request.tokenUrl(),
                request.clientId(),
                request.scopes(),
                request.certificate(),
                request.signatureHeader(),
                request.accessKey(),
                request.region(),
                request.awsService());
    }

    private static AuthDetails authDetails(
            String bearerVariable,
            String tokenUrl,
            String clientId,
            String scopes,
            String certificate,
            String signatureHeader,
            String accessKey,
            String region,
            String awsService) {
        return new AuthDetails(
                blankToNull(tokenUrl),
                blankToNull(clientId),
                splitScopes(scopes),
                blankToNull(certificate),
                blankToNull(bearerVariable),
                blankToNull(accessKey),
                blankToNull(region),
                blankToNull(awsService),
                blankToNull(signatureHeader));
    }

    private static AuthDetails keepStoredSecrets(AuthDetails incoming, AuthDetails stored) {
        if (stored == null) {
            return incoming;
        }
        return new AuthDetails(
                incoming.tokenUrl(),
                incoming.clientId(),
                incoming.scopes(),
                incoming.certificatePem() == null ? stored.certificatePem() : incoming.certificatePem(),
                incoming.bearerVariable(),
                incoming.accessKey() == null ? stored.accessKey() : incoming.accessKey(),
                incoming.region(),
                incoming.awsService(),
                incoming.signatureHeader());
    }

    private static void requireExtendedAuth(AuthType type, AuthDetails details) {
        if (type == AuthType.BEARER_VARIABLE && blank(details.bearerVariable())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bearer variable is required");
        }
        if (type == AuthType.OAUTH2_CLIENT && blank(details.tokenUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token URL is required");
        }
        if (type == AuthType.OAUTH2_CLIENT && blank(details.clientId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Client id is required");
        }
        if (type == AuthType.HMAC && blank(details.signatureHeader())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Signature header is required");
        }
        if (type == AuthType.AWS_SIG_V4 && blank(details.region())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Region is required");
        }
        if (type == AuthType.AWS_SIG_V4 && blank(details.awsService())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service name is required");
        }
    }

    private static List<String> splitScopes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static List<StaticHeader> staticHeaders(List<StaticHeaderRequest> headers) {
        if (headers == null) {
            return List.of();
        }
        for (StaticHeaderRequest header : headers) {
            if (header == null || header.name() == null || header.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Static header name is required");
            }
        }
        return headers.stream().map(header -> new StaticHeader(header.name().trim(), header.value())).toList();
    }

    private static <T extends Enum<T>> T enumValue(String raw, T fallback, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported value");
        }
    }

    private static AuthType authType(String raw) {
        try {
            return raw == null || raw.isBlank() ? AuthType.NONE : AuthType.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported auth type");
        }
    }

    private static List<String> successStatuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    /** Drops the secret. Callers only learn whether one is stored. */
    private static ServiceResponse response(ServiceView view) {
        AuthConfig auth = view.sharedAuth();
        boolean secretPresent = auth.secret() != null && !auth.secret().isBlank();
        AuthDetails details = auth.details();
        return new ServiceResponse(
                view.serviceKey(),
                view.name(),
                view.description(),
                view.baseUrls(),
                new SharedAuthResponse(
                        auth.type(),
                        auth.apiKeyHeader(),
                        secretPresent,
                        auth.apiKeyLocation(),
                        details.bearerVariable(),
                        details.tokenUrl(),
                        details.clientId(),
                        details.scopes(),
                        details.certificatePem() != null && !details.certificatePem().isBlank(),
                        details.signatureHeader(),
                        details.accessKey() != null && !details.accessKey().isBlank(),
                        details.region(),
                        details.awsService()),
                view.operations());
    }

    private static List<InputParameter> inputs(List<InputParameterRequest> parameters) {
        if (parameters == null) {
            return List.of();
        }
        return parameters.stream()
                .map(parameter -> new InputParameter(
                        parameter.name(),
                        ParameterType.valueOf(parameter.type()),
                        parameter.required(),
                        ParameterLocation.valueOf(parameter.location()),
                        parameter.partKind() == null || parameter.partKind().isBlank()
                                ? PartKind.TEXT
                                : PartKind.valueOf(parameter.partKind())))
                .toList();
    }

    private static List<OutputParameter> outputs(List<OutputParameterRequest> parameters) {
        if (parameters == null) {
            return List.of();
        }
        return parameters.stream()
                .map(parameter -> new OutputParameter(
                        parameter.name(),
                        ParameterType.valueOf(parameter.type()),
                        parameter.jsonField(),
                        parameter.source() == null || parameter.source().isBlank()
                                ? OutputSource.JSON_FIELD
                                : OutputSource.valueOf(parameter.source()),
                        blankToNull(parameter.headerName())))
                .toList();
    }

    @PostMapping("/{serviceKey}/publish")
    public ApiResponse<Void> publish(@PathVariable String serviceKey) {
        catalog.publish(serviceKey);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{serviceKey}/operations/{operationKey}")
    public ApiResponse<Void> deleteOperation(@PathVariable String serviceKey, @PathVariable String operationKey) {
        try {
            catalog.deleteOperation(serviceKey, operationKey);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
        return ApiResponse.ok();
    }

    @DeleteMapping("/{serviceKey}")
    public ApiResponse<Void> deleteService(@PathVariable String serviceKey) {
        try {
            catalog.deleteService(serviceKey);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
        return ApiResponse.ok();
    }

    @GetMapping("/{serviceKey}")
    public ApiResponse<ServiceResponse> get(@PathVariable String serviceKey) {
        return ApiResponse.ok(response(catalog.find(serviceKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))));
    }

    public record CreateServiceRequest(String serviceKey, String name) {
    }

    public record SaveServiceSettingsRequest(
            String name,
            String description,
            List<EnvironmentBaseUrl> baseUrls,
            SharedAuthRequest sharedAuth
    ) {
    }

    public record SharedAuthRequest(
            String type,
            String apiKeyHeader,
            String secret,
            String apiKeyLocation,
            String bearerVariable,
            String tokenUrl,
            String clientId,
            String scopes,
            String certificate,
            String signatureHeader,
            String accessKey,
            String region,
            String awsService) {
    }

    public record ServiceResponse(
            String serviceKey,
            String name,
            String description,
            List<EnvironmentBaseUrl> baseUrls,
            SharedAuthResponse sharedAuth,
            List<io.github.illuseahashmap.serviceregistry.domain.OperationView> operations
    ) {
    }

    /** Auth returned to the browser. The secret itself is never included. */
    public record SharedAuthResponse(
            AuthType type,
            String apiKeyHeader,
            boolean secretPresent,
            ApiKeyLocation apiKeyLocation,
            String bearerVariable,
            String tokenUrl,
            String clientId,
            List<String> scopes,
            boolean certificatePresent,
            String signatureHeader,
            boolean accessKeyPresent,
            String region,
            String awsService) {
    }

    public record SaveOperationRequest(
            String method,
            String url,
            String authType,
            String apiKeyHeader,
            String secret,
            String bodyTemplate,
            int timeoutMillis,
            List<String> errorStatusCodes,
            String bpmnErrorCode,
            List<InputParameterRequest> inputParameters,
            List<OutputParameterRequest> outputParameters,
            String authMode,
            Integer connectTimeoutMillis,
            List<ErrorRuleRequest> errorRules,
            String successStatuses,
            String contentType,
            String rawMediaType,
            List<StaticHeaderRequest> staticHeaders,
            String bodyPresence,
            String apiKeyLocation,
            String bearerVariable,
            String tokenUrl,
            String clientId,
            String scopes,
            String certificate,
            String signatureHeader,
            String accessKey,
            String region,
            String awsService,
            StepRequest steps
    ) {
    }

    public record StepRequest(
            String pagination,
            String nextField,
            String nextTokenParameter,
            Integer maxPages,
            String callStyle,
            String correlationOutput,
            Integer waitTimeoutMillis) {
    }

    public record StaticHeaderRequest(String name, String value) {
    }

    public record ErrorRuleRequest(List<String> patterns, String bpmnErrorCode) {
    }

    public record InputParameterRequest(
            String name, String type, boolean required, String location, String partKind) {
    }

    public record OutputParameterRequest(
            String name, String type, String jsonField, String source, String headerName) {
    }
}
