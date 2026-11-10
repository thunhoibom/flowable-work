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
        events.setNonMatchingEventConsumer(new WorkflowNonMatchingEventConsumer());
        eventRegistry = events.getEventRegistry();
        eventRepository = events.getEventRepositoryService();
        eventRepository.createDeployment().addEventDefinition("payment.event", PAYMENT_EVENT).deploy();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    /** A wait on paymentReceived correlated on orderId, mapping amount to paidAmount. */
    static final String PAYMENT_WAIT = """
            <flowable:eventType>paymentReceived</flowable:eventType>
            <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
            <flowable:eventOutParameter source="amount" target="paidAmount"/>""";

    static String definitions(String key, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/>
                    %s
                  </process>
                </definitions>""".formatted(key, body);
    }

    static String process(String key, String waitExtensions) {
        return definitions(key, """
                <receiveTask id="wait">
                  <extensionElements>%s</extensionElements>
                </receiveTask>
                <userTask id="after"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>""".formatted(waitExtensions));
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
        deployXml(key, process(key, waitExtensions));
    }

    private static void deployXml(String key, String xml) {
        engine.getRepositoryService().createDeployment().addString(key + ".bpmn20.xml", xml).deploy();
    }

    private static void send(String orderId, double amount) {
        eventRegistry.sendEventToConsumers(paymentEvent(eventRepository, orderId, amount));
    }

    private static boolean waiting(ProcessInstance instance) {
        return at(instance, "wait");
    }

    private static boolean at(ProcessInstance instance, String activityId) {
        return engine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId(activityId).count() > 0;
    }

    private static ProcessInstance start(String key, Object orderId) {
        return engine.getRuntimeService().startProcessInstanceByKey(key, Map.of("orderId", orderId));
    }

    private static long subscriptions(ProcessInstance instance) {
        return engine.getRuntimeService().createEventSubscriptionQuery().processInstanceId(instance.getId()).count();
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

    @Test
    void intermediateCatchWithoutDefinitionWaitsForTheEvent() {
        deployXml("factsCatch", definitions("factsCatch", """
                <intermediateCatchEvent id="wait"><extensionElements>%s</extensionElements></intermediateCatchEvent>
                <userTask id="after"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>""".formatted(PAYMENT_WAIT)));
        ProcessInstance instance = start("factsCatch", "C-1");

        EventSubscription subscription = engine.getRuntimeService().createEventSubscriptionQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(subscription.getActivityId()).isEqualTo("wait");

        send("C-1", 7.0);

        assertThat(at(instance, "after")).isTrue();
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "paidAmount")).isEqualTo(7.0);
    }

    private static String boundaryProcess(String key, boolean cancelActivity) {
        return definitions(key, """
                <userTask id="work"/>
                <boundaryEvent id="evt" attachedToRef="work" cancelActivity="%s">
                  <extensionElements>%s</extensionElements>
                </boundaryEvent>
                <userTask id="afterWork"/>
                <userTask id="afterEvent"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <sequenceFlow id="f2" sourceRef="work" targetRef="afterWork"/>
                <sequenceFlow id="f3" sourceRef="evt" targetRef="afterEvent"/>""".formatted(cancelActivity, PAYMENT_WAIT));
    }

    @Test
    void interruptingBoundaryCancelsTheTask() {
        deployXml("factsBoundaryCancel", boundaryProcess("factsBoundaryCancel", true));
        ProcessInstance instance = start("factsBoundaryCancel", "B-1");

        send("B-1", 1.0);

        assertThat(at(instance, "work")).isFalse();
        assertThat(at(instance, "afterEvent")).isTrue();
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "paidAmount")).isEqualTo(1.0);
    }

    @Test
    void nonInterruptingBoundaryKeepsTheTask() {
        deployXml("factsBoundaryKeep", boundaryProcess("factsBoundaryKeep", false));
        ProcessInstance instance = start("factsBoundaryKeep", "B-2");

        send("B-2", 1.0);

        assertThat(at(instance, "work")).isTrue();
        assertThat(at(instance, "afterEvent")).isTrue();
    }

    @Test
    void eventBasedGatewayTakesTheEventBranchAndDropsTheTimer() {
        // R-H1: the alternative the deploy guard suggests for a receive task after such a gateway.
        deployXml("factsGateway", definitions("factsGateway", """
                <eventBasedGateway id="g"/>
                <intermediateCatchEvent id="wait"><extensionElements>%s</extensionElements></intermediateCatchEvent>
                <intermediateCatchEvent id="timer">
                  <timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition>
                </intermediateCatchEvent>
                <userTask id="afterEvent"/>
                <userTask id="afterTimer"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
                <sequenceFlow id="f2" sourceRef="g" targetRef="wait"/>
                <sequenceFlow id="f3" sourceRef="g" targetRef="timer"/>
                <sequenceFlow id="f4" sourceRef="wait" targetRef="afterEvent"/>
                <sequenceFlow id="f5" sourceRef="timer" targetRef="afterTimer"/>""".formatted(PAYMENT_WAIT)));
        ProcessInstance instance = start("factsGateway", "G-1");
        assertThat(engine.getManagementService().createTimerJobQuery().processInstanceId(instance.getId()).count())
                .isEqualTo(1);

        send("G-1", 1.0);

        assertThat(at(instance, "afterEvent")).isTrue();
        assertThat(engine.getManagementService().createTimerJobQuery().processInstanceId(instance.getId()).count())
                .isZero();
    }

    @Test
    void timerFiredFirstLeavesNothingToMatch() {
        deployXml("factsTimerFirst", definitions("factsTimerFirst", """
                <receiveTask id="wait"><extensionElements>%s</extensionElements></receiveTask>
                <boundaryEvent id="timeout" attachedToRef="wait" cancelActivity="true">
                  <timerEventDefinition><timeDuration>PT1S</timeDuration></timerEventDefinition>
                </boundaryEvent>
                <userTask id="after"/>
                <userTask id="afterTimer"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>
                <sequenceFlow id="f3" sourceRef="timeout" targetRef="afterTimer"/>""".formatted(PAYMENT_WAIT)));
        ProcessInstance instance = start("factsTimerFirst", "T-1");
        var management = engine.getManagementService();
        String timerId = management.createTimerJobQuery().processInstanceId(instance.getId()).singleResult().getId();
        String jobId = management.moveTimerToExecutableJob(timerId).getId();
        management.executeJob(jobId);
        assertThat(at(instance, "afterTimer")).isTrue();
        assertThat(subscriptions(instance)).isZero();

        try (InboundDispatchContext context = InboundDispatchContext.open()) {
            send("T-1", 1.0);

            assertThat(context.noMatchReported()).isTrue();
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.NO_MATCH);
        }
    }

    @Test
    void numberVariableMatchesTextField() {
        // DefaultCorrelationKeyGenerator hashes toString() of each value.
        deploy("factsNumber", """
                <flowable:eventType>paymentReceived</flowable:eventType>
                <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>""");
        ProcessInstance instance = start("factsNumber", 123);

        send("123", 1.0);

        assertThat(waiting(instance)).isFalse();
    }

    @Test
    void asyncLeaveOnACatchEventLeavesThroughAJob() {
        deployXml("factsAsyncLeave", definitions("factsAsyncLeave", """
                <intermediateCatchEvent id="wait" flowable:asyncLeave="true">
                  <extensionElements>%s</extensionElements>
                </intermediateCatchEvent>
                <userTask id="after"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>""".formatted(PAYMENT_WAIT)));
        ProcessInstance instance = start("factsAsyncLeave", "L-1");

        send("L-1", 1.0);

        assertThat(at(instance, "after")).isFalse();
        var management = engine.getManagementService();
        var job = management.createJobQuery().processInstanceId(instance.getId()).singleResult();
        assertThat(job).isNotNull();
        management.executeJob(job.getId());
        assertThat(at(instance, "after")).isTrue();
    }
}
