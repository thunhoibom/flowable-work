package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.process.infrastructure.flowable.script.WorkflowScriptConfigurator;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlowableEngineConfig {

    @Bean
    public ProcessEngineConfigurationConfigurer processEngineConfigurationConfigurer(
            ScriptTaskProperties scriptTaskProperties) {
        return configuration -> {
            configuration.setCreateDiagramOnDeploy(false);
            // Always installed: a deployed script task must never fall back to an unsandboxed engine.
            configuration.addConfigurator(new WorkflowScriptConfigurator(scriptTaskProperties));
        };
    }
}
