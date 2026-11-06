package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.flowable.bpmn.model.ScriptTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.behavior.ScriptTaskActivityBehavior;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.IOParameterUtil;

/**
 * Replaces Flowable's script behavior for every script task. Keeps skip expressions and leaving from the
 * parent; only the evaluation changes: declared inputs only, no execution object, Rhino sandbox.
 */
public class WorkflowScriptTaskActivityBehavior extends ScriptTaskActivityBehavior {

    private static final long serialVersionUID = 1L;
    static final String JAVASCRIPT = "javascript";

    public WorkflowScriptTaskActivityBehavior(ScriptTask scriptTask) {
        super(scriptTask.getId(), scriptTask.getScript(), scriptTask.getScriptFormat(),
                scriptTask.getResultVariable(), scriptTask.getSkipExpression(), false);
    }

    @Override
    protected void executeScript(DelegateExecution execution) {
        if (!JAVASCRIPT.equals(language)) {
            throw new ScriptTaskException(scriptTaskId, "only scriptFormat javascript is supported");
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (execution.getCurrentFlowElement() instanceof ScriptTask scriptTask
                && scriptTask.getInParameters() != null && !scriptTask.getInParameters().isEmpty()) {
            BiConsumer<String, Object> collect = inputs::put;
            // flowable:in marked transient is still just an input of this script.
            IOParameterUtil.processInParameters(scriptTask.getInParameters(), execution, collect, collect,
                    CommandContextUtil.getProcessEngineConfiguration().getExpressionManager());
        }
        Object result = SandboxedJavascript.evaluate(scriptTaskId, script, inputs);
        if (resultVariable != null && !resultVariable.isBlank()) {
            execution.setVariable(resultVariable, result);
        }
    }
}
