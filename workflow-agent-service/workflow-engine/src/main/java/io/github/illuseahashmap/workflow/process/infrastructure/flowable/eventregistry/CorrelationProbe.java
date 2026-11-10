package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import io.github.illuseahashmap.workflow.process.application.dto.WaitCorrelationView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.CorrelationUtil;
import org.flowable.engine.impl.util.ProcessDefinitionUtil;

/**
 * Read-only: the current values of an event wait's correlation expressions, and whether they still
 * give the key its subscription was created with. Only plain variable paths whose root is a variable
 * of the execution are evaluated; anything else could call a bean, so it is reported as not evaluated
 * and the change check is skipped. Values are never logged.
 */
public class CorrelationProbe implements Command<CorrelationProbe.Result> {

    private static final Pattern SIMPLE_PATH =
            Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)(\\.[A-Za-z_][A-Za-z0-9_]*)*}$");

    /** @param changed null when at least one value could not be evaluated */
    public record Result(List<WaitCorrelationView> correlations, Boolean changed) {
    }

    private final String executionId;
    private final String activityId;
    private final String subscriptionConfiguration;

    public CorrelationProbe(String executionId, String activityId, String subscriptionConfiguration) {
        this.executionId = executionId;
        this.activityId = activityId;
        this.subscriptionConfiguration = subscriptionConfiguration;
    }

    @Override
    public Result execute(CommandContext context) {
        ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager(context).findById(executionId);
        if (execution == null) {
            return new Result(List.of(), null);
        }
        FlowElement element = ProcessDefinitionUtil.getBpmnModel(execution.getProcessDefinitionId())
                .getFlowElement(activityId);
        if (element == null) {
            return new Result(List.of(), null);
        }
        var expressions = CommandContextUtil.getProcessEngineConfiguration(context).getExpressionManager();
        var transformer = CommandContextUtil.getEventRegistryEngineConfiguration(context)
                .getCorrelationValueTransformer();
        List<WaitCorrelationView> fields = new ArrayList<>();
        boolean all = true;
        for (ExtensionElement parameter : element.getExtensionElements()
                .getOrDefault(BpmnXMLConstants.ELEMENT_EVENT_CORRELATION_PARAMETER, List.of())) {
            String name = parameter.getAttributeValue(null, "name");
            String expression = parameter.getAttributeValue(null, "value");
            var path = expression == null ? null : SIMPLE_PATH.matcher(expression.trim());
            if (path == null || !path.matches() || !execution.hasVariable(path.group(1))) {
                fields.add(new WaitCorrelationView(name, expression, null, false));
                all = false;
                continue;
            }
            try {
                Object value = transformer.transformRawValue(
                        expressions.createExpression(expression.trim()).getValue(execution));
                fields.add(new WaitCorrelationView(name, expression, value == null ? null : value.toString(), true));
            } catch (RuntimeException exception) {
                fields.add(new WaitCorrelationView(name, expression, null, false));
                all = false;
            }
        }
        Boolean changed = all
                ? !Objects.equals(CorrelationUtil.getCorrelationKey(
                        BpmnXMLConstants.ELEMENT_EVENT_CORRELATION_PARAMETER, context, element, execution),
                        subscriptionConfiguration)
                : null;
        return new Result(fields, changed);
    }
}
