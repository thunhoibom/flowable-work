package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.RestInboundChannelModelProcessor;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.WorkflowEventRegistryCustomizer;
import org.flowable.eventregistry.api.management.EventRegistryChangeDetectionExecutor;
import org.flowable.eventregistry.spring.SpringEventRegistryEngineConfiguration;
import org.flowable.eventregistry.spring.management.DefaultSpringEventRegistryChangeDetectionExecutor;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.spring.boot.eventregistry.FlowableEventRegistryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlowableEventRegistryConfig {

    /**
     * Flowable's auto-configuration also collects ChannelModelProcessor beans; the customizer only
     * adds this one when it is not already registered, so it ends up there exactly once.
     */
    @Bean
    public RestInboundChannelModelProcessor restInboundChannelModelProcessor() {
        return new RestInboundChannelModelProcessor();
    }

    /**
     * Replaces Flowable's default change detection executor, which looks up a single TaskScheduler
     * bean and fails because the application has several (Agent worker and completion schedulers).
     * This constructor gives the executor its own single-thread scheduler.
     */
    @Bean
    public EventRegistryChangeDetectionExecutor eventRegistryChangeDetectionExecutor(
            FlowableEventRegistryProperties properties) {
        return new DefaultSpringEventRegistryChangeDetectionExecutor(
                properties.getChangeDetectionInitialDelay().toMillis(),
                properties.getChangeDetectionDelay().toMillis());
    }

    @Bean
    public EngineConfigurationConfigurer<SpringEventRegistryEngineConfiguration> workflowEventRegistryConfigurer(
            RestInboundChannelModelProcessor restChannels) {
        return configuration -> WorkflowEventRegistryCustomizer.customize(configuration, restChannels);
    }
}
