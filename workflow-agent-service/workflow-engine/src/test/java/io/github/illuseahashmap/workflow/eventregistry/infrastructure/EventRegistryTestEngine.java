package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.RestInboundChannelModelProcessor;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.WorkflowBpmnEventRegistryEventConsumer;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.WorkflowEventRegistryCustomizer;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;
import org.flowable.eventregistry.impl.cfg.StandaloneInMemEventRegistryEngineConfiguration;
import org.flowable.eventregistry.impl.configurator.EventRegistryEngineConfigurator;

/** An in-memory process engine with the platform's event registry extensions, as in production. */
final class EventRegistryTestEngine implements AutoCloseable {

    final RestInboundChannelModelProcessor channels = new RestInboundChannelModelProcessor();
    final EventRegistryEngineConfiguration events = new StandaloneInMemEventRegistryEngineConfiguration();
    final ProcessEngine engine;
    final FlowableEventRegistryGateway gateway;

    EventRegistryTestEngine(String name) {
        WorkflowEventRegistryCustomizer.customize(events, channels);
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        configuration.setEventRegistryConfigurator(
                new EventRegistryEngineConfigurator().setEventEngineConfiguration(events));
        configuration.setEventRegistryEventConsumer(new WorkflowBpmnEventRegistryEventConsumer(configuration));
        engine = configuration.buildProcessEngine();
        gateway = new FlowableEventRegistryGateway(events.getEventRepositoryService(), engine.getRuntimeService());
    }

    /** A process waiting in a receive event task correlated on orderId, then a user task. */
    static String waitingProcess(String processKey, String eventKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/>
                    <receiveTask id="wait">
                      <extensionElements>
                        <flowable:eventType>%s</flowable:eventType>
                        <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                        <flowable:eventOutParameter source="amount" target="paidAmount"/>
                      </extensionElements>
                    </receiveTask>
                    <userTask id="after"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>
                  </process>
                </definitions>""".formatted(processKey, eventKey);
    }

    void deployWaitingProcess(String tenantId, String processKey, String eventKey) {
        engine.getRepositoryService().createDeployment().tenantId(tenantId)
                .addString(processKey + ".bpmn20.xml", waitingProcess(processKey, eventKey)).deploy();
    }

    @Override
    public void close() {
        engine.close();
    }
}
