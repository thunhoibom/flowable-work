package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiveTaskOperationServiceImplTest {

    private static final String TENANT = "tenant-wait";
    private static final TenantProvider TENANT_PROVIDER = () -> new TenantContext.TenantInfo(TENANT, "wait", "Wait");
    private static ProcessEngine engine;

    private WorkflowOperationAuditService audit;
    private ReceiveTaskOperationServiceImpl service;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:receive-task-ops;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        deploy("manual", "<receiveTask id=\"wait\" name=\"Chờ xác nhận\"/>");
        deploy("agentLike", """
                <receiveTask id="wait"><extensionElements>
                  <workflow:agentTask xmlns:workflow="http://workflow-agent.local/bpmn" agentVersionId="1"/>
                </extensionElements></receiveTask>""");
        deploy("eventWait", """
                <receiveTask id="wait"><extensionElements>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></receiveTask>""");
        deploy("parallelWait", """
                <receiveTask id="wait">
                  <multiInstanceLoopCharacteristics isSequential="false">
                    <loopCardinality>2</loopCardinality>
                  </multiInstanceLoopCharacteristics>
                </receiveTask>""");
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static void deploy(String key, String step) {
        engine.getRepositoryService().createDeployment().tenantId(TENANT).addString(key + ".bpmn20.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/>%s<userTask id="after"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>
                  </process>
                </definitions>""".formatted(key, step)).deploy();
    }

    private static ProcessInstance start(String key) {
        return engine.getRuntimeService().startProcessInstanceByKeyAndTenantId(key, Map.of(), TENANT);
    }

    private static boolean waiting(ProcessInstance instance) {
        return engine.getRuntimeService().createActivityInstanceQuery()
                .processInstanceId(instance.getId()).activityId("wait").unfinished().count() > 0;
    }

    private static ErrorCode code(Throwable error) {
        return ((BusinessException) error).getErrorCode();
    }

    @BeforeEach
    void setUp() {
        audit = mock(WorkflowOperationAuditService.class);
        ProcessInstanceTransactionExecutor executor = mock(ProcessInstanceTransactionExecutor.class);
        when(executor.execute(anyString(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        service = new ReceiveTaskOperationServiceImpl(engine.getRuntimeService(), engine.getRepositoryService(),
                engine.getHistoryService(), executor, TENANT_PROVIDER, audit);
    }

    private static TriggerReceiveTaskRequest request(ProcessInstance instance, String executionId,
                                                     Map<String, Object> variables) {
        return new TriggerReceiveTaskRequest(instance.getId(), "wait", executionId, variables, "Đã gọi điện xác nhận");
    }

    @Test
    void listsManualWaitsOnly() {
        ProcessInstance manual = start("manual");
        ProcessInstance agent = start("agentLike");
        ProcessInstance event = start("eventWait");

        assertThat(service.waits(manual.getId())).singleElement().satisfies(wait -> {
            assertThat(wait.kind()).isEqualTo("MANUAL");
            assertThat(wait.activityId()).isEqualTo("wait");
            assertThat(wait.activityName()).isEqualTo("Chờ xác nhận");
            assertThat(wait.executionId()).isNotBlank();
            assertThat(wait.since()).isNotNull();
        });
        assertThat(service.waits(agent.getId())).isEmpty();
        assertThat(service.waits(event.getId())).isEmpty();
    }

    @Test
    void triggersAManualWaitWithVariablesAndAuditsOnlyTheirNames() {
        ProcessInstance instance = start("manual");

        service.trigger(request(instance, null, Map.of("confirmed", true)));

        assertThat(waiting(instance)).isFalse();
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "confirmed")).isEqualTo(true);
        verify(audit).record(eq("RECEIVE_TASK_TRIGGERED"), eq(instance.getId()), eq("manual"), isNull(),
                eq("wait"), eq("WAITING"), eq("TRIGGERED"), endsWith("| variables=confirmed"));
    }

    @Test
    void refusesAgentAndEventWaits() {
        for (String key : List.of("agentLike", "eventWait")) {
            ProcessInstance instance = start(key);
            assertThatThrownBy(() -> service.trigger(request(instance, null, Map.of())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không phải bước chờ thủ công")
                    .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.CONFLICT));
            assertThat(waiting(instance)).isTrue();
        }
    }

    @Test
    void multiInstanceNeedsAnExecutionId() {
        ProcessInstance instance = start("parallelWait");
        assertThat(service.waits(instance.getId())).hasSize(2);
        assertThatThrownBy(() -> service.trigger(request(instance, null, Map.of())))
                .hasMessageContaining("executionId");
        assertThatThrownBy(() -> service.trigger(request(instance, "not-a-branch", Map.of())))
                .hasMessageContaining("executionId");

        String first = service.waits(instance.getId()).getFirst().executionId();
        service.trigger(request(instance, first, Map.of()));

        assertThat(service.waits(instance.getId())).hasSize(1);
    }

    @Test
    void rejectsReservedVariablesAndUnknownInstances() {
        ProcessInstance instance = start("manual");
        assertThatThrownBy(() -> service.trigger(request(instance, null, Map.of("tenantId", "x"))))
                .hasMessageContaining("tenantId");
        assertThat(waiting(instance)).isTrue();
        assertThatThrownBy(() -> service.trigger(new TriggerReceiveTaskRequest(
                "missing", "wait", null, Map.of(), "r")))
                .satisfies(error -> assertThat(code(error)).isEqualTo(ErrorCode.NOT_FOUND));

        // Definitions exist only for TENANT: the tenant filter in the service relies on this.
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("manual"))
                .isInstanceOf(FlowableObjectNotFoundException.class);
    }
}
