package io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import io.github.illuseahashmap.workflow.config.SubProcessProperties;
import io.github.illuseahashmap.workflow.process.application.WorkflowInteractionGuard;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowSubProcessStartInterceptorTest {

    private static final String TENANT = "tenant-interceptor";
    private static final WorkflowInteractionGuard GUARD = mock(WorkflowInteractionGuard.class);
    private static final AtomicReference<TenantContext.TenantInfo> TENANT_SEEN = new AtomicReference<>();
    private static ProcessEngine engine;

    @BeforeAll
    static void start() {
        SubProcessProperties properties = new SubProcessProperties();
        properties.setMaxCallDepth(2);
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:subprocess-interceptor;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        configuration.setStartProcessInstanceInterceptor(
                new WorkflowSubProcessStartInterceptor(properties, () -> GUARD, null));
        engine = configuration.buildProcessEngine();

        deploy("leaf", """
                <process id="leaf" isExecutable="true">
                  <startEvent id="s"/><userTask id="t"/><endEvent id="e"/>
                  <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                  <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
                </process>""");
        deploy("caller", caller("caller", "leaf",
                "<flowable:in source=\"fake\" target=\"tenantId\"/>"
                        + "<flowable:in source=\"orderId\" target=\"orderId\"/>"));
        deploy("middle", caller("middle", "leaf", ""));
        deploy("top", caller("top", "middle", ""));
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void clearGuard() {
        reset(GUARD);
        TENANT_SEEN.set(null);
    }

    private static String caller(String key, String callee, String extensions) {
        return """
                <process id="%s" isExecutable="true">
                  <startEvent id="s"/>
                  <callActivity id="call" calledElement="%s">
                    <extensionElements>%s</extensionElements>
                  </callActivity>
                  <endEvent id="e"/>
                  <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                  <sequenceFlow id="f2" sourceRef="call" targetRef="e"/>
                </process>""".formatted(key, callee, extensions);
    }

    private static void deploy(String key, String process) {
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                     xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                        %s
                        </definitions>""".formatted(process)).deploy();
    }

    private static ProcessInstance startCaller(Map<String, Object> variables) {
        return engine.getRuntimeService().startProcessInstanceByKeyAndTenantId("caller", variables, TENANT);
    }

    private static ProcessInstance child(ProcessInstance parent) {
        return engine.getRuntimeService().createProcessInstanceQuery()
                .superProcessInstanceId(parent.getId()).singleResult();
    }

    @Test
    void childGetsTheParentTenantEvenWhenAnInParameterTriesToOverrideIt() {
        ProcessInstance parent = startCaller(Map.of(
                "tenantId", TENANT, "tenantCode", "real", "fake", "other-tenant", "orderId", "O-1"));

        Map<String, Object> childVariables = engine.getRuntimeService().getVariables(child(parent).getId());

        assertThat(childVariables)
                .containsEntry("tenantId", TENANT)
                .containsEntry("tenantCode", "real")
                .containsEntry("orderId", "O-1");
    }

    @Test
    void validatesTheChildStartContractWithoutTenantVariables() {
        doThrow(new AssertionError("unused")).when(GUARD).validateStart(eq("never"), anyString(), anyMap());
        org.mockito.Mockito.doAnswer(call -> {
            TENANT_SEEN.set(TenantContext.current());
            return null;
        }).when(GUARD).validateStart(eq("leaf"), anyString(), anyMap());

        startCaller(Map.of("tenantId", TENANT, "tenantCode", "real", "orderId", "O-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(GUARD).validateStart(eq("leaf"), anyString(), variables.capture());
        assertThat(variables.getValue()).containsEntry("orderId", "O-1")
                .doesNotContainKeys("tenantId", "tenantCode");
        assertThat(TENANT_SEEN.get().tenantId()).isEqualTo(TENANT);
        assertThat(TENANT_SEEN.get().tenantCode()).isEqualTo("real");
    }

    @Test
    void aMissingChildInputRollsBackTheWholeStart() {
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "Required Agent input is missing: Số tiền"))
                .when(GUARD).validateStart(eq("leaf"), anyString(), anyMap());
        long before = engine.getHistoryService().createHistoricProcessInstanceQuery().count();

        assertThatThrownBy(() -> startCaller(Map.of("tenantId", TENANT, "tenantCode", "real")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("leaf")
                .hasMessageContaining("Số tiền");
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().count()).isEqualTo(before);
    }

    @Test
    void stopsAChainLongerThanTheMaximumCallDepth() {
        assertThatThrownBy(() -> engine.getRuntimeService()
                .startProcessInstanceByKeyAndTenantId("top", Map.of(), TENANT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("depth 2");
        verify(GUARD, never()).validateStart(eq("leaf"), anyString(), anyMap());
    }

    @Test
    void worksForAParentWithoutTenantVariables() {
        ProcessInstance parent = startCaller(Map.of("orderId", "O-2"));

        assertThat(engine.getRuntimeService().getVariables(child(parent).getId()))
                .containsEntry("orderId", "O-2");
    }
}
