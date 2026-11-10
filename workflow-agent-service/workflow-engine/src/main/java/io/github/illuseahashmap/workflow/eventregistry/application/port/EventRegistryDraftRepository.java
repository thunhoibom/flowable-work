package io.github.illuseahashmap.workflow.eventregistry.application.port;

import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryDraft;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import java.util.List;
import java.util.Optional;

public interface EventRegistryDraftRepository {

    Optional<RegistryDraft> find(String tenantId, RegistryModelType type, String key);

    /** Locks the row until the transaction ends. */
    Optional<RegistryDraft> findForUpdate(String tenantId, RegistryModelType type, String key);

    /** False when a draft with that key already exists. */
    boolean insert(RegistryDraft draft);

    /** False when the stored revision is no longer {@code expectedRevision}. */
    boolean update(RegistryDraft draft, long expectedRevision);

    List<RegistryDraft> list(String tenantId, RegistryModelType type);

    void delete(String tenantId, RegistryModelType type, String key);
}
