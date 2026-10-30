package io.github.illuseahashmap.serviceregistry.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryServiceCatalog implements ServiceCatalog {

    private final Map<String, ServiceDraft> drafts = new HashMap<>();
    private final Map<String, List<PublishedOperation>> publications = new HashMap<>();

    @Override
    public void saveDraft(ServiceDraft draft) {
        for (PublishedOperation operation : draft.operations()) {
            ParameterContracts.check(operation.inputs(), operation.outputs());
        }
        drafts.put(draft.serviceKey(), draft);
    }

    @Override
    public void saveService(String serviceKey, String name) {
        ServiceDraft existing = drafts.get(serviceKey);
        List<PublishedOperation> operations = existing == null ? List.of() : existing.operations();
        drafts.put(serviceKey, new ServiceDraft(serviceKey, name, operations));
    }

    @Override
    public void saveOperation(String serviceKey, PublishedOperation operation) {
        ParameterContracts.check(operation.inputs(), operation.outputs());
        ServiceDraft existing = drafts.get(serviceKey);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown service");
        }
        List<PublishedOperation> operations = new ArrayList<>(existing.operations());
        String secret = operation.secret();
        if (secret == null) {
            secret = operations.stream()
                    .filter(current -> current.operationKey().equals(operation.operationKey()))
                    .findFirst()
                    .map(PublishedOperation::secret)
                    .orElse(null);
        }
        operations.removeIf(current -> current.operationKey().equals(operation.operationKey()));
        operations.add(new PublishedOperation(
                operation.operationKey(),
                operation.method(),
                operation.urlTemplate(),
                operation.authType(),
                operation.apiKeyHeader(),
                secret,
                operation.bodyTemplate(),
                operation.timeoutMillis(),
                operation.errorStatusCodes(),
                operation.bpmnErrorCode(),
                operation.inputs(),
                operation.outputs()));
        drafts.put(serviceKey, new ServiceDraft(serviceKey, existing.name(), List.copyOf(operations)));
    }

    @Override
    public void publish(String serviceKey) {
        ServiceDraft draft = drafts.get(serviceKey);
        if (draft == null) {
            return;
        }
        publications.put(serviceKey, List.copyOf(draft.operations()));
    }

    @Override
    public Optional<PublishedOperation> published(String serviceKey, String operationKey) {
        List<PublishedOperation> operations = publications.get(serviceKey);
        if (operations == null) {
            return Optional.empty();
        }
        return operations.stream()
                .filter(operation -> operation.operationKey().equals(operationKey))
                .findFirst();
    }

    @Override
    public Optional<ServiceView> find(String serviceKey) {
        ServiceDraft draft = drafts.get(serviceKey);
        if (draft == null) {
            return Optional.empty();
        }
        Map<String, PublishedOperation> published = new HashMap<>();
        for (PublishedOperation operation : publications.getOrDefault(serviceKey, List.of())) {
            published.put(operation.operationKey(), operation);
        }
        List<OperationView> operations = draft.operations().stream()
                .map(operation -> {
                    PublishedOperation snapshot = published.get(operation.operationKey());
                    return new OperationView(
                            operation.operationKey(),
                            operation.method(),
                            operation.urlTemplate(),
                            operation.authType(),
                            operation.apiKeyHeader(),
                            operation.secret() != null,
                            operation.bodyTemplate(),
                            operation.timeoutMillis(),
                            operation.errorStatusCodes(),
                            operation.bpmnErrorCode(),
                            snapshot == null ? null : snapshot.urlTemplate(),
                            snapshot == null ? null : snapshot.method(),
                            snapshot == null ? null : snapshot.bpmnErrorCode(),
                            operation.inputs(),
                            operation.outputs(),
                            snapshot == null ? List.of() : snapshot.inputs(),
                            snapshot == null ? List.of() : snapshot.outputs());
                })
                .toList();
        return Optional.of(new ServiceView(draft.serviceKey(), draft.name(), operations));
    }

    @Override
    public List<ServiceView> list() {
        return drafts.keySet().stream()
                .map(this::find)
                .flatMap(Optional::stream)
                .toList();
    }
}
