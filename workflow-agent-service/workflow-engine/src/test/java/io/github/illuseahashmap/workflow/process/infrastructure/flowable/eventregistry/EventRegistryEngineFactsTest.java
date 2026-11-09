package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flowable.common.engine.impl.interceptor.EngineConfigurationConstants;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.api.runtime.EventPayloadInstance;
import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;
import org.flowable.eventregistry.impl.event.FlowableEventRegistryEvent;
import org.flowable.eventregistry.impl.runtime.EventInstanceImpl;
import org.flowable.eventregistry.impl.runtime.EventPayloadInstanceImpl;
import org.flowable.eventregistry.model.EventModel;
import org.flowable.eventregistry.model.EventPayload;
import org.flowable.eventsubscription.api.EventSubscription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins down the Flowable 8.0.0 event registry behaviour that spec 2026-11-09-event-registry-d1-design
 * relies on. No platform code is involved: if Flowable changes, these tests fail first.
 */
class EventRegistryEngineFactsTest {

    static final String PAYMENT_EVENT = """
            {"key":"paymentReceived","name":"Payment received","payload":[
              {"name":"orderId","type":"string","correlationParameter":true},
              {"name":"amount","type":"double"}]}""";

    private static ProcessEngine engine;
    private static EventRegistry eventRegistry;
    private static EventRepositoryService eventRepository;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:event-registry-facts;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        var events = (EventRegistryEngineConfiguration) configuration.getEngineConfigurations()
                .get(EngineConfigurationConstants.KEY_EVENT_REGISTRY_CONFIG);
        eventRegistry = events.getEventRegistry();
        eventRepository = events.getEventRepositoryService();
        eventRepository.createDeployment().addEventDefinition("payment.event", PAYMENT_EVENT).deploy();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    static String process(String key, String waitExtensions) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/>
                    <receiveTask id="wait">
                      <extensionElements>%s</extensionElements>
                    </receiveTask>
                    <userTask id="after"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>
                  </process>
                </definitions>""".formatted(key, waitExtensions);
    }

    static EventPayload field(EventModel model, String name) {
        return model.getPayload().stream()
                .filter(payload -> payload.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    static FlowableEventRegistryEvent paymentEvent(EventRepositoryService repository, String orderId, double amount) {
        EventModel model = repository.getEventModelByKey("paymentReceived");
        List<EventPayloadInstance> payload = List.of(
                new EventPayloadInstanceImpl(field(model, "orderId"), orderId),
                new EventPayloadInstanceImpl(field(model, "amount"), amount));
        return new FlowableEventRegistryEvent(new EventInstanceImpl("paymentReceived", payload));
    }

    private static void deploy(String key, String waitExtensions) {
        engine.getRepositoryService().createDeployment()
                .addString(key + ".bpmn20.xml", process(key, waitExtensions)).deploy();
    }

    private static void send(String orderId, double amount) {
        eventRegistry.sendEventToConsumers(paymentEvent(eventRepository, orderId, amount));
    }

    private static boolean waiting(ProcessInstance instance) {
        return engine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("wait").count() > 0;
    }

    @Test
    void eventTypeTurnsReceiveTaskIntoSubscriptionWithHashedCorrelation() {
        deploy("factsSubscription", """
                <flowable:eventType>paymentReceived</flowable:eventType>
                <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>""");
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey("factsSubscription", Map.of("orderId", "SO-1"));

        EventSubscription subscription = engine.getRuntimeService().createEventSubscriptionQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(subscription.getEventType()).isEqualTo("paymentReceived");
        assertThat(subscription.getActivityId()).isEqualTo("wait");
        assertThat(subscription.getConfiguration())
                .isEqualTo(eventRegistry.generateKey(Map.of("orderId", "SO-1")));
    }

    @Test
    void matchingEventTriggersAndMapsPayloadOtherInstancesStay() {
        deploy("factsMatch", """
                <flowable:eventType>paymentReceived</flowable:eventType>
                <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                <flowable:eventOutParameter source="amount" target="paidAmount"/>""");
        ProcessInstance a = engine.getRuntimeService().startProcessInstanceByKey("factsMatch", Map.of("orderId", "A"));
        ProcessInstance b = engine.getRuntimeService().startProcessInstanceByKey("factsMatch", Map.of("orderId", "B"));

        send("A", 120.5);

        assertThat(waiting(a)).isFalse();
        assertThat(engine.getRuntimeService().getVariable(a.getId(), "paidAmount")).isEqualTo(120.5);
        assertThat(waiting(b)).isTrue();
    }

    @Test
    void waitWithoutCorrelationIsTriggeredByAnyEventOfThatKey() {
        // R-B1: the reason the deploy guard will demand at least one correlation parameter.
        deploy("factsNoCorrelation", "<flowable:eventType>paymentReceived</flowable:eventType>");
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("factsNoCorrelation");

        send("whatever", 1.0);

        assertThat(waiting(instance)).isFalse();
    }

    @Test
    void redeployingTheSameEventJsonBumpsTheVersion() {
        // R-E1: the reason event models are deployed only when their content changes.
        long before = eventRepository.createEventDefinitionQuery().eventDefinitionKey("paymentReceived").count();
        eventRepository.createDeployment().addEventDefinition("payment.event", PAYMENT_EVENT).deploy();

        assertThat(eventRepository.createEventDefinitionQuery().eventDefinitionKey("paymentReceived").count())
                .isEqualTo(before + 1);
    }
}
