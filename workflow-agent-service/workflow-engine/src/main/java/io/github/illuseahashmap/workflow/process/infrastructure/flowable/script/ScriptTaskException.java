package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import org.flowable.common.engine.api.FlowableException;

/** Technical failure of a sandboxed script task. Never a BPMN error; the process transaction rolls back. */
public class ScriptTaskException extends FlowableException {

    private static final long serialVersionUID = 1L;

    public ScriptTaskException(String taskId, String reason) {
        super("Script task " + taskId + " failed: " + reason);
    }

    public ScriptTaskException(String taskId, String reason, Throwable cause) {
        super("Script task " + taskId + " failed: " + reason, cause);
    }
}
