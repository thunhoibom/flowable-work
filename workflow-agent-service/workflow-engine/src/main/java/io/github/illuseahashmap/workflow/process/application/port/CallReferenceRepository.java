package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.domain.CallReference;
import java.util.List;

public interface CallReferenceRepository {

    /** Replaces every reference of one process version. */
    void replaceForDefinition(String tenantId, String parentDefinitionId, List<CallReference> references);

    void deleteForDefinition(String tenantId, String parentDefinitionId);

    void deleteForKey(String tenantId, String parentDefinitionKey);

    /** Every version that calls {@code calledKey}. Callers decide which versions still count. */
    List<CallReference> findCallers(String tenantId, String calledKey);

    List<CallReference> findByDefinition(String tenantId, String parentDefinitionId);

    List<CallReference> findAll(String tenantId);
}
