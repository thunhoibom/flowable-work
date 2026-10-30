package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.ParameterCoercion;
import io.github.illuseahashmap.workflow.process.application.ServiceParameterException;
import io.github.illuseahashmap.workflow.process.application.port.PublishedCallContract;
import io.github.illuseahashmap.workflow.process.application.port.ServiceInputContract;
import io.github.illuseahashmap.workflow.process.application.port.ServiceOutputContract;
import io.github.illuseahashmap.workflow.process.application.port.ServiceRegistryCallException;
import io.github.illuseahashmap.workflow.process.application.port.ServiceRegistryGateway;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.springframework.stereotype.Component;

@Component("serviceRegistryDelegate")
public class ServiceRegistryDelegate implements JavaDelegate {

    private static final String WORKFLOW_NAMESPACE = "http://workflow-agent.local/bpmn";

    private final ServiceRegistryGateway gateway;
    private final ExpressionManager expressionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ServiceRegistryDelegate(ServiceRegistryGateway gateway, ProcessEngineConfigurationImpl configuration) {
        this.gateway = gateway;
        this.expressionManager = configuration.getExpressionManager();
    }

    @Override
    public void execute(DelegateExecution execution) {
        ExtensionElement binding = binding(execution.getCurrentFlowElement());
        String serviceKey = attribute(binding, "serviceKey");
        String operationKey = attribute(binding, "operationKey");
        PublishedCallContract contract = gateway.findPublished(serviceKey, operationKey)
                .orElseThrow(() -> new BpmnError("SERVICE_NOT_PUBLISHED"));
        Map<String, String> expressions = readField(attribute(binding, "inputMapping"), "expression");
        Map<String, String> variables = readField(attribute(binding, "outputMapping"), "variable");
        Map<String, Object> resolved = resolveInputs(execution, serviceKey, operationKey, contract, expressions);
        Map<String, Object> result;
        try {
            result = gateway.invoke(serviceKey, operationKey, resolved);
        } catch (ServiceRegistryCallException exception) {
            throw new BpmnError(exception.bpmnErrorCode());
        }
        Map<String, Object> outputs = coerceOutputs(serviceKey, operationKey, contract, variables, result);
        outputs.forEach(execution::setVariable);
    }

    private Map<String, Object> resolveInputs(DelegateExecution execution,
                                               String serviceKey,
                                               String operationKey,
                                               PublishedCallContract contract,
                                               Map<String, String> expressions) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (ServiceInputContract input : contract.inputs()) {
            Object value = value(execution, serviceKey, operationKey, input, expressions.get(input.name()));
            if (value != null) {
                resolved.put(input.name(), value);
            }
        }
        return resolved;
    }

    private Object value(DelegateExecution execution,
                         String serviceKey,
                         String operationKey,
                         ServiceInputContract input,
                         String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return required(serviceKey, operationKey, input, null);
        }
        Object raw;
        try {
            raw = evaluate(execution, expression.trim());
        } catch (RuntimeException exception) {
            if (exception instanceof BpmnError) {
                throw exception;
            }
            throw new ServiceParameterException(
                    failure(serviceKey, operationKey, input.name(), "expression failed"), exception);
        }
        if (raw == null) {
            return required(serviceKey, operationKey, input, null);
        }
        try {
            return required(serviceKey, operationKey, input, ParameterCoercion.coerce(input.type(), raw));
        } catch (IllegalArgumentException exception) {
            throw new ServiceParameterException(
                    failure(serviceKey, operationKey, input.name(), "cannot coerce value"), exception);
        }
    }

    private Object required(String serviceKey, String operationKey, ServiceInputContract input, Object value) {
        if (value == null && input.required()) {
            throw new ServiceParameterException(
                    failure(serviceKey, operationKey, input.name(), "required value is missing"));
        }
        return value;
    }

    private Map<String, Object> coerceOutputs(String serviceKey,
                                               String operationKey,
                                               PublishedCallContract contract,
                                               Map<String, String> variables,
                                               Map<String, Object> result) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (ServiceOutputContract output : contract.outputs()) {
            String variable = variables.get(output.name());
            if (variable == null || variable.isBlank() || !result.containsKey(output.name())) {
                continue;
            }
            try {
                Object value = ParameterCoercion.coerce(output.type(), result.get(output.name()));
                if (value != null) {
                    outputs.put(variable, value);
                }
            } catch (IllegalArgumentException exception) {
                throw new ServiceParameterException(
                        failure(serviceKey, operationKey, output.name(), "cannot coerce value"), exception);
            }
        }
        return outputs;
    }

    private Object evaluate(DelegateExecution execution, String expression) {
        if (expression.contains("${") || expression.contains("#{")) {
            return expressionManager.createExpression(expression).getValue(execution);
        }
        return expression;
    }

    private Map<String, String> readField(String json, String field) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode node : array(json)) {
            JsonNode name = node.get("name");
            JsonNode selected = node.get(field);
            if (name == null || name.isNull() || selected == null || selected.isNull()) {
                continue;
            }
            String text = selected.asText();
            if ("variable".equals(field) && text.isBlank()) {
                continue;
            }
            values.put(name.asText(), text);
        }
        return values;
    }

    private List<JsonNode> array(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<JsonNode> nodes = new java.util.ArrayList<>();
            root.forEach(nodes::add);
            return nodes;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("mapping is not JSON", exception);
        }
    }

    private static String failure(String serviceKey, String operationKey, String parameter, String reason) {
        return "service " + serviceKey + " operation " + operationKey + " parameter " + parameter + ": " + reason;
    }

    private ExtensionElement binding(FlowElement current) {
        if (current == null || current.getExtensionElements() == null) {
            throw new BpmnError("SERVICE_NOT_PUBLISHED");
        }
        return current.getExtensionElements().values().stream()
                .flatMap(List::stream)
                .filter(extension -> "serviceCall".equals(extension.getName())
                        && WORKFLOW_NAMESPACE.equals(extension.getNamespace()))
                .findFirst()
                .orElseThrow(() -> new BpmnError("SERVICE_NOT_PUBLISHED"));
    }

    private String attribute(ExtensionElement binding, String name) {
        String value = binding.getAttributeValue(null, name);
        if (value == null) {
            value = binding.getAttributeValue(WORKFLOW_NAMESPACE, name);
        }
        return value;
    }
}
