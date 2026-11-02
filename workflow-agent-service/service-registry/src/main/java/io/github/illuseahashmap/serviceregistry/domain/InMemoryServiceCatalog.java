package io.github.illuseahashmap.serviceregistry.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryServiceCatalog implements ServiceCatalog {

    private final Map<String, ServiceDraft> drafts = new HashMap<>();
    /** Published copies. Draft edits must not mutate these lists or secrets. */
    private final Map<String, PublishedService> publications = new HashMap<>();

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
        if (existing == null) {
            drafts.put(serviceKey, new ServiceDraft(serviceKey, name, List.of()));
            return;
        }
        // Renaming keeps the connection settings already stored on the draft.
        drafts.put(serviceKey, copy(existing, name, existing.description(), existing.baseUrls(), existing.sharedAuth(), existing.operations()));
    }

    @Override
    public void saveServiceSettings(
            String serviceKey,
            String name,
            String description,
            List<EnvironmentBaseUrl> baseUrls,
            AuthConfig sharedAuth) {
        ServiceDraft existing = drafts.get(serviceKey);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown service");
        }
        drafts.put(serviceKey, copy(existing, name, description, baseUrls, sharedAuth, existing.operations()));
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
            // A blank secret on save keeps the secret already stored for this operation.
            secret = operations.stream()
                    .filter(current -> current.operationKey().equals(operation.operationKey()))
                    .findFirst()
                    .map(PublishedOperation::secret)
                    .orElse(null);
        }
        operations.removeIf(current -> current.operationKey().equals(operation.operationKey()));
        operations.add(copyOperation(operation, secret));
        drafts.put(serviceKey, copy(existing, existing.name(), existing.description(), existing.baseUrls(), existing.sharedAuth(), operations));
    }

    @Override
    public void deleteOperation(String serviceKey, String operationKey) {
        ServiceDraft existing = drafts.get(serviceKey);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown service");
        }
        boolean found = existing.operations().stream()
                .anyMatch(operation -> operation.operationKey().equals(operationKey));
        if (!found) {
            throw new IllegalArgumentException("Unknown operation");
        }
        List<PublishedOperation> remaining = existing.operations().stream()
                .filter(operation -> !operation.operationKey().equals(operationKey))
                .toList();
        drafts.put(serviceKey, copy(existing, existing.name(), existing.description(), existing.baseUrls(), existing.sharedAuth(), remaining));
    }

    @Override
    public void deleteService(String serviceKey) {
        if (drafts.remove(serviceKey) == null) {
            throw new IllegalArgumentException("Unknown service");
        }
        publications.remove(serviceKey);
    }

    @Override
    public void publish(String serviceKey) {
        ServiceDraft draft = drafts.get(serviceKey);
        if (draft == null) {
            return;
        }
        // Copy into a new snapshot so a later draft edit cannot change this publication.
        publications.put(serviceKey, new PublishedService(
                draft.serviceKey(),
                draft.name(),
                draft.description(),
                draft.baseUrls(),
                draft.sharedAuth(),
                draft.operations()));
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
        return Optional.ofNullable(publications.get(serviceKey));
    }

    @Override
    public Optional<ServiceView> find(String serviceKey) {
        ServiceDraft draft = drafts.get(serviceKey);
        if (draft == null) {
            return Optional.empty();
        }
        Map<String, PublishedOperation> published = new HashMap<>();
        publishedService(serviceKey).ifPresent(service -> {
            for (PublishedOperation operation : service.operations()) {
                published.put(operation.operationKey(), operation);
            }
        });
        List<OperationView> operations = draft.operations().stream()
                .map(operation -> toView(operation, published.get(operation.operationKey())))
                .toList();
        return Optional.of(new ServiceView(
                draft.serviceKey(),
                draft.name(),
                draft.description(),
                draft.baseUrls(),
                draft.sharedAuth(),
                operations));
    }

    @Override
    public List<ServiceView> list() {
        return drafts.keySet().stream()
                .map(this::find)
                .flatMap(Optional::stream)
                .toList();
    }

    private static ServiceDraft copy(
            ServiceDraft existing,
            String name,
            String description,
            List<EnvironmentBaseUrl> baseUrls,
            AuthConfig sharedAuth,
            List<PublishedOperation> operations) {
        return new ServiceDraft(existing.serviceKey(), name, description, baseUrls, sharedAuth, operations);
    }

    /** Keeps auth mode, both timeouts, and every error rule when the secret is filled in. */
    private static PublishedOperation copyOperation(PublishedOperation operation, String secret) {
        return new PublishedOperation(
                operation.operationKey(),
                operation.method(),
                operation.urlTemplate(),
                operation.authMode(),
                operation.authType(),
                operation.apiKeyHeader(),
                secret,
                operation.bodyTemplate(),
                operation.connectTimeoutMillis(),
                operation.readTimeoutMillis(),
                operation.errorRules(),
                operation.successStatuses(),
                operation.inputs(),
                operation.outputs());
    }

    private static OperationView toView(PublishedOperation operation, PublishedOperation snapshot) {
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
                snapshot == null ? List.of() : snapshot.outputs(),
                operation.authMode(),
                operation.connectTimeoutMillis(),
                operation.errorRules(),
                operation.successStatuses());
    }
}
