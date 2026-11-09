package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

class WaitStateKindsTest {

    private static ReceiveTask receiveTask(String extensionName, String text) {
        ReceiveTask task = new ReceiveTask();
        task.setId("wait");
        if (extensionName != null) {
            ExtensionElement extension = new ExtensionElement();
            extension.setName(extensionName);
            extension.setElementText(text);
            task.addExtensionElement(extension);
        }
        return task;
    }

    @Test
    void onlyPlainReceiveTasksAreManualWaits() {
        assertThat(WaitStateKinds.isManualWait(receiveTask(null, null))).isTrue();
        assertThat(WaitStateKinds.isManualWait(receiveTask("agentTask", null))).isFalse();
        assertThat(WaitStateKinds.isManualWait(receiveTask("eventType", "paid"))).isFalse();
        assertThat(WaitStateKinds.isManualWait(new UserTask())).isFalse();
        assertThat(WaitStateKinds.isManualWait(new ServiceTask())).isFalse();
        assertThat(WaitStateKinds.isManualWait(null)).isFalse();
    }

    @Test
    void eventWaitNeedsANonBlankEventType() {
        assertThat(WaitStateKinds.isEventWait(receiveTask("eventType", "paid"))).isTrue();
        assertThat(WaitStateKinds.isEventWait(receiveTask("eventType", " "))).isFalse();
        assertThat(WaitStateKinds.isEventWait(receiveTask(null, null))).isFalse();
        // A blank event type is a plain receive task for Flowable, so it is a manual wait.
        assertThat(WaitStateKinds.isManualWait(receiveTask("eventType", " "))).isTrue();
    }
}
