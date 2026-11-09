package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.flowable.common.engine.impl.interceptor.EngineConfigurationConstants;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorkflowEventConsumersTest {

    /** Fails the step after the wait when the instance has fail=true. */
    public static class FailingDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            if (Boolean.TRUE.equals(execution.getVariable("fail"))) {
                throw new IllegalStateException("downstream failure");
            }
        }
    }

    private static ProcessEngine engine;
    private static EventRegistry eventRegistry;
    private static EventRepositoryService eventRepository;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:event-consumers;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        configuration.setEventRegistryEventConsumer(new WorkflowBpmnEventRegistryEventConsumer(configuration));
        engine = configuration.buildProcessEngine();
        var events = (EventRegistryEngineConfiguration) configuration.getEngineConfigurations()
                .get(EngineConfigurationConstants.KEY_EVENT_REGISTRY_CONFIG);
        events.setNonMatchingEventConsumer(new WorkflowNonMatchingEventConsumer());
        eventRegistry = events.getEventRegistry();
        eventRepository = events.getEventRepositoryService();
        eventRepository.createDeployment()
                .addEventDefinition("payment.event", EventRegistryEngineFactsTest.PAYMENT_EVENT).deploy();
        engine.getRepositoryService().createDeployment().addString("consumers.bpmn20.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="consumers" isExecutable="true">
                    <startEvent id="s"/>
                    <receiveTask id="wait">
                      <extensionElements>
                        <flowable:eventType>paymentReceived</flowable:eventType>
                        <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                      </extensionElements>
                    </receiveTask>
                    <serviceTask id="next" flowable:class="%s"/>
                    <userTask id="after"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="next"/>
                    <sequenceFlow id="f3" sourceRef="next" targetRef="after"/>
                  </process>
                </definitions>""".formatted(FailingDelegate.class.getName())).deploy();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static void send(String orderId) {
        eventRegistry.sendEventToConsumers(EventRegistryEngineFactsTest.paymentEvent(eventRepository, orderId, 1.0));
    }

    private static boolean waiting(ProcessInstance instance) {
        return engine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("wait").count() > 0;
    }

    @Test
    void oneFailingSubscriptionDoesNotStopTheOthers() {
        ProcessInstance failing = engine.getRuntimeService()
                .startProcessInstanceByKey("consumers", Map.of("orderId", "SAME", "fail", true));
        ProcessInstance healthy = engine.getRuntimeService()
                .startProcessInstanceByKey("consumers", Map.of("orderId", "SAME", "fail", false));

        try (var context = InboundDispatchContext.open()) {
            send("SAME");

            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.PARTIAL);
            assertThat(context.outcomes()).extracting(InboundDispatchContext.Outcome::processInstanceId)
                    .containsExactlyInAnyOrder(failing.getId(), healthy.getId());
            assertThat(context.outcomes()).filteredOn(outcome -> !outcome.succeeded())
                    .singleElement().satisfies(outcome -> {
                        assertThat(outcome.processInstanceId()).isEqualTo(failing.getId());
                        assertThat(outcome.errorCode()).isEqualTo("EVENT_PROCESSING_FAILED");
                    });
        }
        assertThat(waiting(failing)).isTrue();
        assertThat(waiting(healthy)).isFalse();
    }

    @Test
    void noMatchingSubscriptionIsReported() {
        try (var context = InboundDispatchContext.open()) {
            send("NOBODY-WAITS");

            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.NO_MATCH);
            assertThat(context.noMatchReported()).isTrue();
        }
    }

    @Test
    void worksWithoutAnOpenContext() {
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey("consumers", Map.of("orderId", "NO-CONTEXT", "fail", false));

        send("NO-CONTEXT");

        assertThat(waiting(instance)).isFalse();
    }
}
