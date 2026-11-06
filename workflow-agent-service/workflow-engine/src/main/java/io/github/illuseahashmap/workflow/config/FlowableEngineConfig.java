package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.process.application.WorkflowInteractionGuard;
import io.github.illuseahashmap.workflow.process.application.port.WorkflowOperationAuditRepository;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.script.WorkflowScriptConfigurator;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess.SubProcessAuditListener;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess.WorkflowSubProcessStartInterceptor;
import java.util.ArrayList;
import java.util.List;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlowableEngineConfig {

    @Bean
    public ProcessEngineConfigurationConfigurer processEngineConfigurationConfigurer(
            ScriptTaskProperties scriptTaskProperties,
            SubProcessProperties subProcessProperties,
            ObjectProvider<WorkflowInteractionGuard> interactionGuard,
            ObjectProvider<WorkflowOperationAuditRepository> auditRepository) {
        return configuration -> {
            configuration.setCreateDiagramOnDeploy(false);
            // Always installed: a deployed script task must never fall back to an unsandboxed engine.
            configuration.addConfigurator(new WorkflowScriptConfigurator(scriptTaskProperties));
            // Always installed: a deployed call activity always gets trusted tenant variables.
            configuration.setStartProcessInstanceInterceptor(new WorkflowSubProcessStartInterceptor(
                    subProcessProperties, interactionGuard::getObject,
                    configuration.getStartProcessInstanceInterceptor()));
            List<FlowableEventListener> listeners = configuration.getEventListeners() == null
                    ? new ArrayList<>() : new ArrayList<>(configuration.getEventListeners());
            listeners.add(new SubProcessAuditListener(auditRepository::getObject));
            configuration.setEventListeners(listeners);
        };
    }
}
