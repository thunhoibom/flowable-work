package io.github.illuseahashmap.serviceregistry.interfaces;

import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.ErrorRule;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InputParameter;
import io.github.illuseahashmap.serviceregistry.domain.OutputParameter;
import io.github.illuseahashmap.serviceregistry.domain.ParameterLocation;
import io.github.illuseahashmap.serviceregistry.domain.ParameterType;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.domain.ServiceView;
import java.util.Arrays;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
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
                ? new SharedAuthRequest("NONE", null, null)
                : request.sharedAuth();
        AuthType type = authType(sharedAuth.type());
        if (type == AuthType.API_KEY && (sharedAuth.apiKeyHeader() == null || sharedAuth.apiKeyHeader().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API key header is required");
        }
        // A blank secret keeps the secret already stored on the draft.
        String secret = sharedAuth.secret();
        if (secret == null || secret.isBlank()) {
            secret = catalog.find(serviceKey)
                    .map(ServiceView::sharedAuth)
                    .map(AuthConfig::secret)
                    .filter(value -> !value.isBlank())
                    .orElse(null);
        }
        try {
            catalog.saveServiceSettings(serviceKey, request.name().trim(),
                    request.description() == null ? "" : request.description().trim(),
                    baseUrls, new AuthConfig(type, sharedAuth.apiKeyHeader(), secret));
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
        return new PublishedOperation(
                operationKey,
                HttpMethod.valueOf(request.method()),
                request.url(),
                mode,
                AuthType.valueOf(request.authType()),
                request.apiKeyHeader(),
                request.secret(),
                request.bodyTemplate(),
                connectTimeout,
                request.timeoutMillis(),
                rules,
                successStatuses(request.successStatuses()),
                inputs(request.inputParameters()),
                outputs(request.outputParameters()));
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
        return new ServiceResponse(
                view.serviceKey(),
                view.name(),
                view.description(),
                view.baseUrls(),
                new SharedAuthResponse(auth.type(), auth.apiKeyHeader(), secretPresent),
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
                        ParameterLocation.valueOf(parameter.location())))
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
                        parameter.jsonField()))
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

    public record SharedAuthRequest(String type, String apiKeyHeader, String secret) {
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
    public record SharedAuthResponse(AuthType type, String apiKeyHeader, boolean secretPresent) {
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
            String successStatuses
    ) {
    }

    public record ErrorRuleRequest(List<String> patterns, String bpmnErrorCode) {
    }

    public record InputParameterRequest(String name, String type, boolean required, String location) {
    }

    public record OutputParameterRequest(String name, String type, String jsonField) {
    }
}
