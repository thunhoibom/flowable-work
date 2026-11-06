package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** The only place that calls createDeployment. Callers own the transaction. */
@Component
public class ProcessDeployer {

    private final RepositoryService repositoryService;
    private final ProcessReleaseRepository releases;
    private final BpmnDraftInspector inspector;
    private final ScriptTaskDeploymentGuard scriptTaskDeploymentGuard;
    private final AgentTaskBindingParser agentTaskBindingParser;
    private final AgentBindingDeploymentValidator agentBindingDeploymentValidator;
    private final CurrentPrincipalProvider principalProvider;

    public ProcessDeployer(RepositoryService repositoryService, ProcessReleaseRepository releases,
                           BpmnDraftInspector inspector, ScriptTaskDeploymentGuard scriptTaskDeploymentGuard,
                           AgentTaskBindingParser agentTaskBindingParser,
                           AgentBindingDeploymentValidator agentBindingDeploymentValidator,
                           CurrentPrincipalProvider principalProvider) {
        this.repositoryService = repositoryService;
        this.releases = releases;
        this.inspector = inspector;
        this.scriptTaskDeploymentGuard = scriptTaskDeploymentGuard;
        this.agentTaskBindingParser = agentTaskBindingParser;
        this.agentBindingDeploymentValidator = agentBindingDeploymentValidator;
        this.principalProvider = principalProvider;
    }

    /** Every guard, then the deployment. New guards go here, in order. */
    public ProcessDefinition deployChecked(TenantContext.TenantInfo tenant, String key, String name, String xml) {
        inspector.check(key, xml);
        scriptTaskDeploymentGuard.check(xml);
        agentBindingDeploymentValidator.validate(tenant.tenantCode(), agentTaskBindingParser.parse(xml));
        return deployUnchecked(tenant, key, name, xml);
    }

    /** No guards. Only the migration calls this directly, to keep what is already running. */
    public ProcessDefinition deployUnchecked(TenantContext.TenantInfo tenant, String key, String name, String xml) {
        Deployment deployment = repositoryService.createDeployment()
                .name(StringUtils.hasText(name) ? name : key)
                .key(key)
                .tenantId(tenant.tenantId())
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .processDefinitionTenantId(tenant.tenantId())
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "No process definition was deployed");
        }
        if (!key.equals(definition.getKey())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "BPMN process id must equal processDefinitionKey");
        }
        return definition;
    }

    public ProcessRelease record(TenantContext.TenantInfo tenant, ProcessDefinition definition,
                                 ReleaseAction action, Integer sourceVersion, String hash) {
        CurrentPrincipal principal = principalProvider.current();
        String actor = StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
        ProcessRelease release = new ProcessRelease(null, tenant.tenantId(), definition.getKey(),
                definition.getId(), definition.getVersion(), action, sourceVersion, hash, actor,
                OffsetDateTime.now(ZoneOffset.UTC));
        releases.append(release);
        return release;
    }
}
