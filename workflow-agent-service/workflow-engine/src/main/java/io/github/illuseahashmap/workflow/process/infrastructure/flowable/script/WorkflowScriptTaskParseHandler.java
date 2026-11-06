package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import org.flowable.bpmn.model.ScriptTask;
import org.flowable.engine.impl.bpmn.parser.BpmnParse;
import org.flowable.engine.impl.bpmn.parser.handler.ScriptTaskParseHandler;

/** Gives every script task the sandboxed behavior, whatever its scriptFormat. */
public class WorkflowScriptTaskParseHandler extends ScriptTaskParseHandler {

    @Override
    protected void executeParse(BpmnParse bpmnParse, ScriptTask scriptTask) {
        scriptTask.setBehavior(new WorkflowScriptTaskActivityBehavior(scriptTask));
    }
}
