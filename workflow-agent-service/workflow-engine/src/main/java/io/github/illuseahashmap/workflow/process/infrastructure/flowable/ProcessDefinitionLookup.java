package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;

/** Tenant-scoped definition lookups shared by the draft, publish and read services. */
@Component
public class ProcessDefinitionLookup {

    private final RepositoryService repositoryService;

    public ProcessDefinitionLookup(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    public Optional<ProcessDefinition> latest(String tenantId, String key) {
        return Optional.ofNullable(repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).processDefinitionKey(key).latestVersion().singleResult());
    }

    public Optional<ProcessDefinition> version(String tenantId, String key, int version) {
        return Optional.ofNullable(repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).processDefinitionKey(key)
                .processDefinitionVersion(version).singleResult());
    }

    public boolean anyVersion(String tenantId, String key) {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).processDefinitionKey(key).count() > 0;
    }

    public String xml(ProcessDefinition definition) {
        try (InputStream input = repositoryService.getResourceAsStream(
                definition.getDeploymentId(), definition.getResourceName())) {
            if (input == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "BPMN XML resource does not exist");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "BPMN XML cannot be read");
        }
    }
}
