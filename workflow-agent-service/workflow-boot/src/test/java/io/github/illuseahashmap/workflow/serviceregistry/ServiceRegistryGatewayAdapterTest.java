package io.github.illuseahashmap.workflow.serviceregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.serviceregistry.application.PublishedServiceInvoker;
import io.github.illuseahashmap.serviceregistry.application.ServiceCallException;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InputParameter;
import io.github.illuseahashmap.serviceregistry.domain.InvalidServiceRequestException;
import io.github.illuseahashmap.serviceregistry.domain.OutputParameter;
import io.github.illuseahashmap.serviceregistry.domain.ParameterLocation;
import io.github.illuseahashmap.serviceregistry.domain.ParameterType;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.workflow.process.application.ServiceParameterException;
import io.github.illuseahashmap.workflow.process.application.port.ServiceRegistryCallException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceRegistryGatewayAdapterTest {

    @Mock
    private PublishedServiceInvoker invoker;

    @Test
    void returnsMappedVariables() {
        when(invoker.invoke("crm", "createLead", Map.of()))
                .thenReturn(Map.of("leadId", "L1"));
        var adapter = new ServiceRegistryGatewayAdapter(invoker);

        assertThat(adapter.invoke("crm", "createLead", Map.of())).containsEntry("leadId", "L1");
    }

    @Test
    void mapsInvokerFailureToBpmnError() {
        when(invoker.invoke("crm", "createLead", Map.of()))
                .thenThrow(new ServiceCallException("HTTP_ERROR"));
        var adapter = new ServiceRegistryGatewayAdapter(invoker);

        assertThatThrownBy(() -> adapter.invoke("crm", "createLead", Map.of()))
                .isInstanceOfSatisfying(ServiceRegistryCallException.class,
                        error -> assertThat(error.bpmnErrorCode()).isEqualTo("HTTP_ERROR"));
    }

    @Test
    void returnsPublishedContractAndResolvedCall() {
        var operation = new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
                List.of(new InputParameter("name", ParameterType.STRING, true, ParameterLocation.BODY)),
                List.of(new OutputParameter("id", ParameterType.LONG, "id")));
        when(invoker.findPublished("crm", "createLead")).thenReturn(Optional.of(operation));
        when(invoker.invoke("crm", "createLead", Map.of("name", "Ann"))).thenReturn(Map.of("id", 4));
        var adapter = new ServiceRegistryGatewayAdapter(invoker);

        assertThat(adapter.findPublished("crm", "createLead")).hasValueSatisfying(contract ->
                assertThat(contract.inputs().getFirst().required()).isTrue());
        assertThat(adapter.invoke("crm", "createLead", Map.of("name", "Ann"))).containsEntry("id", 4);
    }

    @Test
    void mapsInvalidRequestToParameterException() {
        when(invoker.invoke("crm", "createLead", Map.of()))
                .thenThrow(new InvalidServiceRequestException("URL still contains a placeholder"));
        var adapter = new ServiceRegistryGatewayAdapter(invoker);

        assertThatThrownBy(() -> adapter.invoke("crm", "createLead", Map.of()))
                .isInstanceOf(ServiceParameterException.class)
                .hasMessageContaining("crm")
                .hasMessageContaining("createLead")
                .hasMessageContaining("URL still contains a placeholder");
    }
}
