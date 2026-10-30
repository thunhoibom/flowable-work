package io.github.illuseahashmap.serviceregistry.application;

import io.github.illuseahashmap.serviceregistry.domain.CallOutcome;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import java.util.Map;
import java.util.Optional;

public final class PublishedServiceInvoker {

    private final ServiceCatalog catalog;
    private final ServiceCaller caller;

    public PublishedServiceInvoker(ServiceCatalog catalog, ServiceCaller caller) {
        this.catalog = catalog;
        this.caller = caller;
    }

    public Optional<PublishedOperation> findPublished(String serviceKey, String operationKey) {
        return catalog.published(serviceKey, operationKey);
    }

    public Map<String, Object> invoke(String serviceKey, String operationKey, Map<String, Object> resolvedInputs) {
        PublishedOperation operation = catalog.published(serviceKey, operationKey)
                .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
        CallOutcome outcome = caller.call(operation, resolvedInputs == null ? Map.of() : resolvedInputs);
        if (outcome.failed()) {
            throw new ServiceCallException(outcome.bpmnErrorCode());
        }
        return outcome.variables();
    }
}
