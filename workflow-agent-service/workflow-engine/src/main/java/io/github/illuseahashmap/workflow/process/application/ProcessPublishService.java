package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessReleaseView;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import java.util.List;

/** The only way a process reaches the engine. The latest non-suspended version is the running one. */
public interface ProcessPublishService {

    PublishProcessResult publish(String processDefinitionKey, PublishProcessRequest request);

    /** CI/SERVICE path: overwrite the draft with this XML, then publish it. Unchanged XML is a no-op. */
    PublishProcessResult publishXml(String processDefinitionKey, String name, String bpmnXml);

    PublishProcessResult rollback(String processDefinitionKey, RollbackProcessRequest request);

    ProcessReleaseView suspend(String processDefinitionKey);

    ProcessReleaseView resume(String processDefinitionKey);

    List<ProcessReleaseView> releases(String processDefinitionKey);
}
