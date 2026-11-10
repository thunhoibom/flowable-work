package io.github.illuseahashmap.workflow.eventregistry.application.port;

import io.github.illuseahashmap.workflow.eventregistry.domain.EventReference;
import java.util.List;

/** Which deployed process versions wait for which event. Written by process publishing (slice 5). */
public interface EventReferenceRepository {

    /** Replaces every reference of one process version. */
    void replaceForDefinition(String tenantId, String processDefinitionId, List<EventReference> references);

    void deleteForDefinition(String tenantId, String processDefinitionId);

    /** Every version of the key, found through act_re_procdef: call it before the deployments are deleted. */
    void deleteForProcessKey(String tenantId, String processDefinitionKey);

    /** Latest process definition versions of the tenant that wait for {@code eventKey}. */
    long latestProcessDefinitionsUsing(String tenantId, String eventKey);

    /** Latest process versions that wait for the event, one row per activity. */
    List<EventUsage> usages(String tenantId, String eventKey);

    record EventUsage(String processDefinitionKey, int version, String activityId) {
    }
}
