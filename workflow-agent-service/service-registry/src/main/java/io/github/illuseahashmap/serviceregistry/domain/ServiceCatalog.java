package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;
import java.util.Optional;

public interface ServiceCatalog {

    void saveDraft(ServiceDraft draft);

    void saveService(String serviceKey, String name);

    /**
     * Updates the draft name, description, base URLs, and shared auth.
     * Does not publish, so a running process keeps the previous snapshot.
     */
    void saveServiceSettings(
            String serviceKey,
            String name,
            String description,
            List<EnvironmentBaseUrl> baseUrls,
            AuthConfig sharedAuth);

    void saveOperation(String serviceKey, PublishedOperation operation);

    void publish(String serviceKey);

    void deleteOperation(String serviceKey, String operationKey);

    void deleteService(String serviceKey);

    Optional<PublishedOperation> published(String serviceKey, String operationKey);

    /** The last published snapshot, including base URLs and shared auth. */
    Optional<PublishedService> publishedService(String serviceKey);

    Optional<ServiceView> find(String serviceKey);

    List<ServiceView> list();
}
