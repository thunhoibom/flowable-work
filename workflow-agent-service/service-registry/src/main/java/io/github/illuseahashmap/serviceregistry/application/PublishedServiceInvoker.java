package io.github.illuseahashmap.serviceregistry.application;

import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthInheritance;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.CallOutcome;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import java.util.Map;
import java.util.Optional;

public final class PublishedServiceInvoker {

    private final ServiceCatalog catalog;
    private final ServiceCaller caller;
    private final ServiceEnvironment environment;

    public PublishedServiceInvoker(ServiceCatalog catalog, ServiceCaller caller, ServiceEnvironment environment) {
        this.catalog = catalog;
        this.caller = caller;
        this.environment = environment;
    }

    public Optional<PublishedOperation> findPublished(String serviceKey, String operationKey) {
        // Resolve shared auth so a bearer variable name is visible before the call.
        return catalog.publishedService(serviceKey).flatMap(service -> service.operations().stream()
                .filter(operation -> operation.operationKey().equals(operationKey))
                .findFirst()
                .map(operation -> operation.withCredentials(AuthMode.OVERRIDE, AuthInheritance.resolve(
                        operation.authMode(),
                        service.sharedAuth(),
                        new AuthConfig(
                                operation.authType(),
                                operation.apiKeyHeader(),
                                operation.secret(),
                                operation.apiKeyLocation(),
                                operation.authDetails())))));
    }

    public Map<String, Object> invoke(String serviceKey, String operationKey, Map<String, Object> resolvedInputs) {
        PublishedService service = catalog.publishedService(serviceKey)
                .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
        PublishedOperation operation = service.operations().stream()
                .filter(candidate -> candidate.operationKey().equals(operationKey))
                .findFirst()
                .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
        String baseUrl = baseUrl(service, operation);
        // Copy the resolved credentials onto the operation. RequestFactory does not know about the service.
        PublishedOperation effective = operation.withCredentials(AuthMode.OVERRIDE, AuthInheritance.resolve(
                operation.authMode(),
                service.sharedAuth(),
                new AuthConfig(
                        operation.authType(),
                        operation.apiKeyHeader(),
                        operation.secret(),
                        operation.apiKeyLocation(),
                        operation.authDetails())));
        CallOutcome outcome = caller.call(effective, resolvedInputs == null ? Map.of() : resolvedInputs, baseUrl);
        if (outcome.failed()) {
            throw new ServiceCallException(outcome.bpmnErrorCode());
        }
        return outcome.variables();
    }

    private String baseUrl(PublishedService service, PublishedOperation operation) {
        if (isAbsolute(operation.urlTemplate())) {
            return null;
        }
        return service.baseUrls().stream()
                .filter(item -> environment.name().equals(item.environment()))
                .map(EnvironmentBaseUrl::url)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
    }

    private static boolean isAbsolute(String path) {
        return path != null && (path.startsWith("https://") || path.startsWith("http://"));
    }

}
