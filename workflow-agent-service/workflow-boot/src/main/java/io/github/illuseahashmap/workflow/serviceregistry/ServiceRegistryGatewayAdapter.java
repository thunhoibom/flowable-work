package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.application.PublishedServiceInvoker;
import io.github.illuseahashmap.serviceregistry.application.ServiceCallException;
import io.github.illuseahashmap.serviceregistry.domain.InvalidServiceRequestException;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.workflow.process.application.ServiceParameterException;
import io.github.illuseahashmap.workflow.process.application.ServiceParameterType;
import io.github.illuseahashmap.workflow.process.application.port.PublishedCallContract;
import io.github.illuseahashmap.workflow.process.application.port.ServiceInputContract;
import io.github.illuseahashmap.workflow.process.application.port.ServiceOutputContract;
import io.github.illuseahashmap.workflow.process.application.port.ServiceRegistryCallException;
import io.github.illuseahashmap.workflow.process.application.port.ServiceRegistryGateway;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ServiceRegistryGatewayAdapter implements ServiceRegistryGateway {

    private final PublishedServiceInvoker invoker;

    public ServiceRegistryGatewayAdapter(PublishedServiceInvoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public Optional<PublishedCallContract> findPublished(String serviceKey, String operationKey) {
        return invoker.findPublished(serviceKey, operationKey).map(this::contract);
    }

    @Override
    public Map<String, Object> invoke(String serviceKey, String operationKey, Map<String, Object> resolvedInputs) {
        try {
            return invoker.invoke(serviceKey, operationKey, resolvedInputs);
        } catch (InvalidServiceRequestException exception) {
            throw new ServiceParameterException(
                    "service " + serviceKey + " operation " + operationKey + ": " + exception.getMessage());
        } catch (ServiceCallException exception) {
            throw new ServiceRegistryCallException(exception.bpmnErrorCode());
        }
    }

    private PublishedCallContract contract(PublishedOperation operation) {
        return new PublishedCallContract(
                operation.inputs().stream()
                        .map(input -> new ServiceInputContract(
                                input.name(),
                                ServiceParameterType.valueOf(input.type().name()),
                                input.required()))
                        .toList(),
                operation.outputs().stream()
                        .map(output -> new ServiceOutputContract(
                                output.name(),
                                ServiceParameterType.valueOf(output.type().name())))
                        .toList());
    }
}
