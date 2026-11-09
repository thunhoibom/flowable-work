package io.github.illuseahashmap.workflow.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.RestInboundChannelModel;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.RestInboundChannelModelProcessor;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.WorkflowBpmnEventRegistryEventConsumer;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.WorkflowNonMatchingEventConsumer;
import org.flowable.common.engine.impl.interceptor.EngineConfigurationConstants;
import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The event registry extensions of spec 2026-11-09-event-registry-d1-design are installed exactly once. */
@SpringBootTest(classes = WorkflowAgentServiceApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventRegistryWiringIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private SpringProcessEngineConfiguration processEngineConfiguration;
    @Autowired
    private RestInboundChannelModelProcessor restChannels;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("flowable.database-schema-update", () -> "true");
        registry.add("flowable.async-executor-activate", () -> "false");
        // Worker left enabled on purpose: it adds a second TaskScheduler bean, as in production,
        // which broke Flowable's default change detection executor.
        registry.add("workflow.security.enabled", () -> "false");
        registry.add("workflow.auth.keycloak.server-url", () -> "http://127.0.0.1:9");
        registry.add("workflow.auth.keycloak.realm", () -> "rsa");
        registry.add("workflow.auth.keycloak.client-id", () -> "VETC.WORKFLOW");
        registry.add("workflow.runtime.environment", () -> "test");
    }

    @Test
    void extensionsAreInstalledOnce() {
        var events = (EventRegistryEngineConfiguration) processEngineConfiguration.getEngineConfigurations()
                .get(EngineConfigurationConstants.KEY_EVENT_REGISTRY_CONFIG);

        assertThat(processEngineConfiguration.getEventRegistryEventConsumer())
                .isInstanceOf(WorkflowBpmnEventRegistryEventConsumer.class);
        assertThat(events.getNonMatchingEventConsumer()).isInstanceOf(WorkflowNonMatchingEventConsumer.class);
        assertThat(events.getChannelModelProcessors()).filteredOn(RestInboundChannelModelProcessor.class::isInstance)
                .singleElement().isSameAs(restChannels);
        assertThat(events.isFallbackToDefaultTenant()).isFalse();
        assertThat(events.isEnableEventRegistryChangeDetection()).isTrue();
    }

    @Test
    void restChannelJsonIsUnderstood() {
        var events = (EventRegistryEngineConfiguration) processEngineConfiguration.getEngineConfigurations()
                .get(EngineConfigurationConstants.KEY_EVENT_REGISTRY_CONFIG);

        var model = events.getChannelJsonConverter().convertToChannelModel("""
                {"key":"probe","category":"channel","name":"Probe","channelType":"inbound","type":"rest",
                 "deserializerType":"json","channelEventKeyDetection":{"fixedValue":"x"},
                 "security":{"userIds":["u"],"groupIds":[]}}""");

        assertThat(model).isInstanceOf(RestInboundChannelModel.class);
    }
}
