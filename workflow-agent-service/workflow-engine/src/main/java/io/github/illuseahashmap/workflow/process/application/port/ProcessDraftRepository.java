package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraftHeader;
import java.util.List;
import java.util.Optional;

public interface ProcessDraftRepository {

    Optional<ProcessDraft> find(String tenantId, String processDefinitionKey);

    /** Locks the row until the surrounding transaction ends. */
    Optional<ProcessDraft> findForUpdate(String tenantId, String processDefinitionKey);

    /** @return false when a draft with this key already exists */
    boolean insert(ProcessDraft draft);

    /** @return false when the stored revision is not {@code expectedRevision} */
    boolean update(ProcessDraft draft, long expectedRevision);

    List<ProcessDraftHeader> listHeaders(String tenantId);

    void delete(String tenantId, String processDefinitionKey);
}
