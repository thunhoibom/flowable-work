package io.github.illuseahashmap.serviceregistry.interfaces;

import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InputParameter;
import io.github.illuseahashmap.serviceregistry.domain.OutputParameter;
import io.github.illuseahashmap.serviceregistry.domain.ParameterLocation;
import io.github.illuseahashmap.serviceregistry.domain.ParameterType;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.domain.ServiceView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
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
    public ApiResponse<List<ServiceView>> list() {
        return ApiResponse.ok(catalog.list());
    }

    @PostMapping
    public ApiResponse<Void> create(@RequestBody CreateServiceRequest request) {
        catalog.saveService(request.serviceKey(), request.name());
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
        return new PublishedOperation(
                operationKey,
                HttpMethod.valueOf(request.method()),
                request.url(),
                AuthType.valueOf(request.authType()),
                request.apiKeyHeader(),
                request.secret(),
                request.bodyTemplate(),
                request.timeoutMillis(),
                errorStatusCodes,
                request.bpmnErrorCode(),
                inputs(request.inputParameters()),
                outputs(request.outputParameters()));
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

    @GetMapping("/{serviceKey}")
    public ApiResponse<ServiceView> get(@PathVariable String serviceKey) {
        return ApiResponse.ok(catalog.find(serviceKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    public record CreateServiceRequest(String serviceKey, String name) {
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
            List<OutputParameterRequest> outputParameters
    ) {
    }

    public record InputParameterRequest(String name, String type, boolean required, String location) {
    }

    public record OutputParameterRequest(String name, String type, String jsonField) {
    }
}
