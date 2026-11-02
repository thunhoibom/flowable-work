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
            ParameterContracts.check(operation);
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
        ParameterContracts.check(operation);
        ServiceDraft existing = drafts.get(serviceKey);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown service");
        }
        List<PublishedOperation> operations = new ArrayList<>(existing.operations());
        PublishedOperation stored = operations.stream()
                .filter(current -> current.operationKey().equals(operation.operationKey()))
                .findFirst()
                .orElse(null);
        // A blank secret, certificate, or access key keeps the value already stored.
        String secret = operation.secret() == null && stored != null ? stored.secret() : operation.secret();
        AuthDetails details = keepStoredSecrets(operation.authDetails(), stored == null ? null : stored.authDetails());
        operations.removeIf(current -> current.operationKey().equals(operation.operationKey()));
        operations.add(operation.withCredentials(
                operation.authMode(),
                new AuthConfig(operation.authType(), operation.apiKeyHeader(), secret, operation.apiKeyLocation(), details)));
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
                operation.successStatuses(),
                operation.contentType(),
                operation.rawMediaType(),
                operation.staticHeaders(),
                operation.bodyPresence(),
                operation.apiKeyLocation(),
                operation.authDetails().bearerVariable(),
                operation.authDetails().tokenUrl(),
                operation.authDetails().clientId(),
                operation.authDetails().scopes(),
                present(operation.authDetails().certificatePem()),
                operation.authDetails().signatureHeader(),
                present(operation.authDetails().accessKey()),
                operation.authDetails().region(),
                operation.authDetails().awsService(),
                snapshot == null || snapshot.contentType() == null ? null : snapshot.contentType().name(),
                operation.steps(),
                snapshot == null ? null : snapshot.steps());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
