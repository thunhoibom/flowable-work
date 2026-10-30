package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.ServiceParameterException;
import io.github.illuseahashmap.workflow.process.application.ServiceParameterType;
import io.github.illuseahashmap.workflow.process.application.port.PublishedCallContract;
import io.github.illuseahashmap.workflow.process.application.port.ServiceInputContract;
import io.github.illuseahashmap.workflow.process.application.port.ServiceOutputContract;
import io.github.illuseahashmap.workflow.process.application.port.ServiceRegistryCallException;
import io.github.illuseahashmap.workflow.process.application.port.ServiceRegistryGateway;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.common.engine.impl.el.DefaultExpressionManager;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceRegistryDelegateTest {

    @Mock
    private ServiceRegistryGateway gateway;
    @Mock
    private DelegateExecution execution;
    @Mock
    private ProcessEngineConfigurationImpl configuration;

    private ServiceRegistryDelegate delegate;

    @BeforeEach
    void createDelegate() {
        when(configuration.getExpressionManager()).thenReturn(new DefaultExpressionManager(Map.of()));
        delegate = new ServiceRegistryDelegate(gateway, configuration);
    }

    @Test
    void evaluatesExpressionAndWritesCoercedOutput() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                "[{\"name\":\"customerName\",\"expression\":\"${customerName}\"}]",
                "[{\"name\":\"id\",\"variable\":\"leadId\"}]"));
        when(execution.hasVariable("customerName")).thenReturn(true);
        when(execution.getVariable("customerName")).thenReturn("Ann");
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(stringContract()));
        when(gateway.invoke("crm", "createLead", Map.of("customerName", "Ann"))).thenReturn(Map.of("id", 4));

        delegate.execute(execution);

        verify(execution).setVariable("leadId", 4L);
    }

    @Test
    void passesLiteralTextWithoutEvaluatingIt() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                "[{\"name\":\"customerName\",\"expression\":\"ACTIVE\"}]", "[]"));
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(stringContract()));

        delegate.execute(execution);

        verify(gateway).invoke("crm", "createLead", Map.of("customerName", "ACTIVE"));
    }

    @Test
    void rejectsBlankRequiredExpressionBeforeTheCall() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                "[{\"name\":\"customerName\",\"expression\":\"   \"}]", "[]"));
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(stringContract()));

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(ServiceParameterException.class)
                .hasMessageContaining("customerName")
                .hasMessageContaining("required value is missing");
        verify(gateway, never()).invoke(any(), any(), any());
    }

    @Test
    void wrapsMissingVariableAsExpressionFailure() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                "[{\"name\":\"customerName\",\"expression\":\"${missing}\"}]", "[]"));
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(stringContract()));

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(ServiceParameterException.class)
                .hasMessage("service crm operation createLead parameter customerName: expression failed")
                .matches(error -> error.getCause() != null && !error.getMessage().contains("Ann"));
        verify(gateway, never()).invoke(any(), any(), any());
    }

    @Test
    void wrapsThrownExpressionAndKeepsTheCause() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                "[{\"name\":\"customerName\",\"expression\":\"${customerName}\"}]", "[]"));
        when(execution.hasVariable("customerName")).thenReturn(true);
        when(execution.getVariable("customerName")).thenThrow(new IllegalStateException("absent"));
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(stringContract()));

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(ServiceParameterException.class)
                .hasMessageContaining("customerName")
                .hasMessageContaining("expression failed")
                .matches(error -> error.getCause() != null
                        && !error.getMessage().contains("Ann")
                        && !error.getMessage().contains("absent"));
        verify(gateway, never()).invoke(any(), any(), any());
    }

    @Test
    void rejectsValueThatCannotBeCoerced() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                "[{\"name\":\"customerName\",\"expression\":\"1.5\"}]", "[]"));
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(new PublishedCallContract(
                List.of(new ServiceInputContract("customerName", ServiceParameterType.INTEGER, true)),
                List.of())));

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(ServiceParameterException.class)
                .hasMessageContaining("cannot coerce value");
        verify(gateway, never()).invoke(any(), any(), any());
    }

    @Test
    void ignoresMappingEntryWithoutAnExpression() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                "[{\"name\":\"customerName\",\"location\":\"BODY\"}]", "[]"));
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(stringContract()));

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(ServiceParameterException.class)
                .hasMessageContaining("required value is missing");
        verify(gateway, never()).invoke(any(), any(), any());
    }

    @Test
    void ignoresMappingNamesThatAreNotOnTheContract() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                "[{\"name\":\"gone\",\"expression\":\"${gone}\"}]", "[]"));
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(new PublishedCallContract(
                List.of(), List.of())));

        delegate.execute(execution);

        verify(gateway).invoke("crm", "createLead", Map.of());
    }

    @Test
    void doesNotWriteVariablesWhenOutputCoercionFails() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask(
                "[]", "[{\"name\":\"id\",\"variable\":\"leadId\"}]"));
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(new PublishedCallContract(
                List.of(),
                List.of(new ServiceOutputContract("id", ServiceParameterType.LONG)))));
        when(gateway.invoke("crm", "createLead", Map.of())).thenReturn(Map.of("id", "nope"));

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(ServiceParameterException.class)
                .hasMessageContaining("cannot coerce value");
        verify(execution, never()).setVariable(any(), any());
    }

    @Test
    void mapsCallFailuresToBpmnErrors() {
        for (String code : List.of("SERVICE_NOT_PUBLISHED", "LEAD_CONFLICT", "HTTP_ERROR", "SERVICE_UNAVAILABLE")) {
            reset(gateway, execution);
            when(execution.getCurrentFlowElement()).thenReturn(serviceTask("[]", "[]"));
            when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(new PublishedCallContract(
                    List.of(), List.of())));
            when(gateway.invoke(eq("crm"), eq("createLead"), eq(Map.of())))
                    .thenThrow(new ServiceRegistryCallException(code));

            assertThatThrownBy(() -> delegate.execute(execution))
                    .isInstanceOf(BpmnError.class)
                    .extracting(error -> ((BpmnError) error).getErrorCode())
                    .isEqualTo(code);
        }
    }

    @Test
    void rejectsUnpublishedOperationBeforeTheCall() {
        when(execution.getCurrentFlowElement()).thenReturn(serviceTask("[]", "[]"));
        when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(BpmnError.class)
                .extracting(error -> ((BpmnError) error).getErrorCode())
                .isEqualTo("SERVICE_NOT_PUBLISHED");
        verify(gateway, never()).invoke(any(), any(), any());
    }

    private static PublishedCallContract stringContract() {
        return new PublishedCallContract(
                List.of(new ServiceInputContract("customerName", ServiceParameterType.STRING, true)),
                List.of(new ServiceOutputContract("id", ServiceParameterType.LONG)));
    }

    private static ServiceTask serviceTask(String inputMapping, String outputMapping) {
        ServiceTask task = new ServiceTask();
        task.setId("createLead");
        ExtensionElement call = new ExtensionElement();
        call.setName("serviceCall");
        call.setNamespace("http://workflow-agent.local/bpmn");
        attribute(call, "serviceKey", "crm");
        attribute(call, "operationKey", "createLead");
        attribute(call, "inputMapping", inputMapping);
        attribute(call, "outputMapping", outputMapping);
        task.addExtensionElement(call);
        return task;
    }

    private static void attribute(ExtensionElement element, String name, String value) {
        ExtensionAttribute attribute = new ExtensionAttribute(name);
        attribute.setValue(value);
        element.addAttribute(attribute);
    }
}
