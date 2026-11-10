package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.dto.WaitCorrelationView;
import java.util.HashMap;
import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventsubscription.api.EventSubscription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CorrelationProbeTest {

    /** A bean an expression could call. The probe must never call it. */
    public interface OrderService {
        String key(String orderId);

        String getKey();
    }

    private static final OrderService ORDER_SERVICE = mock(OrderService.class);
    private static ProcessEngine engine;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:correlation-probe;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        Map<Object, Object> beans = new HashMap<>();
        beans.put("orderService", ORDER_SERVICE);
        configuration.setBeans(beans);
        engine = configuration.buildProcessEngine();
        when(ORDER_SERVICE.key(anyString())).thenReturn("K");
        when(ORDER_SERVICE.getKey()).thenReturn("K");
        deploy("probePlain", "${orderId}");
        deploy("probeBean", "${orderService.key(orderId)}");
        deploy("probeBeanPath", "${orderService.key}");
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static void deploy(String key, String correlation) {
        engine.getRepositoryService().createDeployment().addString(key + ".bpmn20.xml",
                EventRegistryEngineFactsTest.process(key, """
                        <flowable:eventType>paymentReceived</flowable:eventType>
                        <flowable:eventCorrelationParameter name="orderId" value="%s"/>""".formatted(correlation)))
                .deploy();
    }

    private static CorrelationProbe.Result probe(ProcessInstance instance) {
        EventSubscription subscription = engine.getRuntimeService().createEventSubscriptionQuery()
                .processInstanceId(instance.getId()).singleResult();
        return engine.getManagementService().executeCommand(new CorrelationProbe(
                subscription.getExecutionId(), subscription.getActivityId(), subscription.getConfiguration()));
    }

    private static ProcessInstance start(String key) {
        return engine.getRuntimeService().startProcessInstanceByKey(key, Map.of("orderId", "A"));
    }

    @Test
    void unchangedVariableIsReportedUnchanged() {
        CorrelationProbe.Result result = probe(start("probePlain"));

        assertThat(result.correlations()).containsExactly(new WaitCorrelationView("orderId", "${orderId}", "A", true));
        assertThat(result.changed()).isFalse();
    }

    @Test
    void variableChangedAfterTheWaitStartedIsReported() {
        ProcessInstance instance = start("probePlain");
        engine.getRuntimeService().setVariable(instance.getId(), "orderId", "B");

        CorrelationProbe.Result result = probe(instance);

        assertThat(result.correlations()).containsExactly(new WaitCorrelationView("orderId", "${orderId}", "B", true));
        assertThat(result.changed()).isTrue();
    }

    @Test
    void beanCallsAreNeverEvaluated() {
        ProcessInstance call = start("probeBean");
        ProcessInstance path = start("probeBeanPath");
        clearInvocations(ORDER_SERVICE);

        CorrelationProbe.Result callResult = probe(call);
        CorrelationProbe.Result pathResult = probe(path);

        assertThat(callResult.correlations()).containsExactly(
                new WaitCorrelationView("orderId", "${orderService.key(orderId)}", null, false));
        assertThat(callResult.changed()).isNull();
        assertThat(pathResult.correlations()).singleElement()
                .satisfies(view -> assertThat(view.evaluated()).isFalse());
        assertThat(pathResult.changed()).isNull();
        verifyNoInteractions(ORDER_SERVICE);
    }
}
