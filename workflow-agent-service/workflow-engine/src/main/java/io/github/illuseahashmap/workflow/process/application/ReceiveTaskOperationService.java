package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessWaitView;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import java.util.List;

/** Manual wait steps (plain receive tasks): who is waiting where, and continuing one. */
public interface ReceiveTaskOperationService {

    List<ProcessWaitView> waits(String processInstanceId);

    void trigger(TriggerReceiveTaskRequest request);
}
