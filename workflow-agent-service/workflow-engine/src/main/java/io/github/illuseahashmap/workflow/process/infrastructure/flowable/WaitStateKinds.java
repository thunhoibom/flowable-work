package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import java.util.List;
import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ReceiveTask;

/** Which kind of wait a receive task is. Mirrors ReceiveTaskParseHandler in Flowable 8.0.0. */
public final class WaitStateKinds {

    private static final String AGENT_TASK = "agentTask";

    private WaitStateKinds() {
    }

    /** A receive task only an explicit trigger continues: no Agent binding, no event type (R-F1). */
    public static boolean isManualWait(FlowElement element) {
        return element instanceof ReceiveTask task
                && !task.getExtensionElements().containsKey(AGENT_TASK)
                && !isEventWait(task);
    }

    /** A receive task Flowable turns into ReceiveEventTaskActivityBehavior. */
    public static boolean isEventWait(FlowElement element) {
        if (!(element instanceof ReceiveTask task)) {
            return false;
        }
        List<ExtensionElement> types = task.getExtensionElements().get(BpmnXMLConstants.ELEMENT_EVENT_TYPE);
        if (types == null || types.isEmpty()) {
            return false;
        }
        String text = types.getFirst().getElementText();
        return text != null && !text.isBlank();
    }
}
