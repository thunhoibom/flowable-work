package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collects what happened to one inbound event on the current thread: which waiting executions
 * were triggered, which failed, or that nothing matched. Opened by whoever hands the event to
 * the engine (the REST channel in slice 4); filled by the event registry consumers.
 */
public final class InboundDispatchContext implements AutoCloseable {

    public enum Result { MATCHED, PARTIAL, NO_MATCH, FAILED }

    public record Outcome(String processInstanceId, String activityId, String errorCode) {
        public boolean succeeded() {
            return errorCode == null;
        }
    }

    private static final ThreadLocal<InboundDispatchContext> CURRENT = new ThreadLocal<>();

    private final List<Outcome> outcomes = new ArrayList<>();
    private boolean noMatch;

    private InboundDispatchContext() {
    }

    public static InboundDispatchContext open() {
        InboundDispatchContext context = new InboundDispatchContext();
        CURRENT.set(context);
        return context;
    }

    public static Optional<InboundDispatchContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public void recordSuccess(String processInstanceId, String activityId) {
        outcomes.add(new Outcome(processInstanceId, activityId, null));
    }

    public void recordFailure(String processInstanceId, String activityId, String errorCode) {
        outcomes.add(new Outcome(processInstanceId, activityId, errorCode));
    }

    public void markNoMatch() {
        noMatch = true;
    }

    public List<Outcome> outcomes() {
        return List.copyOf(outcomes);
    }

    public Result result() {
        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();
        long failed = outcomes.size() - succeeded;
        if (succeeded > 0) {
            return failed > 0 ? Result.PARTIAL : Result.MATCHED;
        }
        return failed > 0 ? Result.FAILED : Result.NO_MATCH;
    }

    boolean noMatchReported() {
        return noMatch;
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
