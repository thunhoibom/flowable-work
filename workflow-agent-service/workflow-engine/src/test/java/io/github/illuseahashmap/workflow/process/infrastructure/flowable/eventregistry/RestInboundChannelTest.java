package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;
import org.flowable.eventregistry.impl.cfg.StandaloneInMemEventRegistryEngineConfiguration;
import org.flowable.eventregistry.impl.configurator.EventRegistryEngineConfigurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RestInboundChannelTest {

    private static final RestInboundChannelModelProcessor CHANNELS = new RestInboundChannelModelProcessor();
    private static ProcessEngine engine;
    private static EventRegistryEngineConfiguration events;

    static String channel(String key, String security) {
        return """
                {"key":"%s","category":"channel","name":"Webhook","channelType":"inbound","type":"rest",
                 "deserializerType":"json","channelEventKeyDetection":{"fixedValue":"paymentReceived"},
                 "allowedEventKeys":["paymentReceived"],"security":%s}""".formatted(key, security);
    }

    @BeforeAll
    static void start() {
        events = new StandaloneInMemEventRegistryEngineConfiguration();
        WorkflowEventRegistryCustomizer.customize(events, CHANNELS);
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:rest-channel;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        configuration.setEventRegistryConfigurator(
                new EventRegistryEngineConfigurator().setEventEngineConfiguration(events));
        configuration.setEventRegistryEventConsumer(new WorkflowBpmnEventRegistryEventConsumer(configuration));
        engine = configuration.buildProcessEngine();
        events.getEventRepositoryService().createDeployment()
                .addEventDefinition("payment.event", EventRegistryEngineFactsTest.PAYMENT_EVENT).deploy();
        engine.getRepositoryService().createDeployment().addString("rest.bpmn20.xml",
                EventRegistryEngineFactsTest.process("restWait", """
                        <flowable:eventType>paymentReceived</flowable:eventType>
                        <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                        <flowable:eventOutParameter source="amount" target="paidAmount"/>""")).deploy();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @Test
    void restChannelJsonIsParsedRegisteredAndDeliversThroughThePipeline() {
        events.getEventRepositoryService().createDeployment()
                .addChannelDefinition("pay.channel",
                        channel("payWebhook", "{\"userIds\":[\"erp-client\"],\"groupIds\":[]}"))
                .deploy();
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey("restWait", Map.of("orderId", "R-1"));

        RestInboundChannelModel model = CHANNELS.find(null, "payWebhook").orElseThrow();
        assertThat(model.getAllowedEventKeys()).containsExactly("paymentReceived");
        assertThat(model.getSecurity().getUserIds()).containsExactly("erp-client");
        assertThat(model.getInboundEventProcessingPipeline()).isNotNull();

        try (var context = InboundDispatchContext.open()) {
            events.getEventRegistry().eventReceived(model, "{\"orderId\":\"R-1\",\"amount\":42.0}");
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.MATCHED);
        }
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "paidAmount")).isEqualTo(42.0);
    }

    @Test
    void channelWithoutAnyAllowedPrincipalIsRejectedAtDeploy() {
        assertThatThrownBy(() -> events.getEventRepositoryService().createDeployment()
                .addChannelDefinition("open.channel", channel("openWebhook", "{\"userIds\":[],\"groupIds\":[]}"))
                .deploy())
                .hasMessageContaining("openWebhook");
        assertThat(CHANNELS.find(null, "openWebhook")).isEmpty();
    }
}
