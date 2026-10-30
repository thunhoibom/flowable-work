package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;
import java.util.Optional;

public interface ServiceCatalog {

    void saveDraft(ServiceDraft draft);

    void saveService(String serviceKey, String name);

    void saveOperation(String serviceKey, PublishedOperation operation);

    void publish(String serviceKey);

    void deleteOperation(String serviceKey, String operationKey);

    void deleteService(String serviceKey);

    Optional<PublishedOperation> published(String serviceKey, String operationKey);

    Optional<ServiceView> find(String serviceKey);

    List<ServiceView> list();
}
