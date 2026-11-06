package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import java.util.List;
import java.util.Map;

public interface ProcessReleaseRepository {

    void append(ProcessRelease release);

    /** Newest first. */
    List<ProcessRelease> list(String tenantId, String processDefinitionKey);

    boolean hasAny(String tenantId, String processDefinitionKey);

    /** The newest release row per process definition id. */
    Map<String, ProcessRelease> latestByDefinition(String tenantId);

    void deleteByKey(String tenantId, String processDefinitionKey);
}
