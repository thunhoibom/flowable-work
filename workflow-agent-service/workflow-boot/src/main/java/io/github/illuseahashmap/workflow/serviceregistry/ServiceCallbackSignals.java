package io.github.illuseahashmap.workflow.serviceregistry;

import java.util.Map;

/** Completes or fails the paused service task. Kept apart from Flowable so the wait can be tested. */
public interface ServiceCallbackSignals {

    void trigger(String executionId, Map<String, Object> variables);

    void fail(String executionId);
}
