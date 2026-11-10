package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import java.util.List;

/**
 * What happened to one inbound event. {@code status} is MATCHED, PARTIAL or DUPLICATE on success,
 * otherwise the error code; {@code replayed} marks a duplicate Idempotency-Key.
 */
public record InboundEventResult(
        int httpStatus,
        String status,
        String code,
        String message,
        List<Delivery> matched,
        List<Delivery> failed,
        boolean replayed) {

    public InboundEventResult {
        matched = matched == null ? List.of() : List.copyOf(matched);
        failed = failed == null ? List.of() : List.copyOf(failed);
    }

    /** One waiting execution the event reached; {@code errorCode} is set when triggering it failed. */
    public record Delivery(String processInstanceId, String activityId, String errorCode) {
    }

    public static InboundEventResult fail(int httpStatus, String code, String message) {
        return new InboundEventResult(httpStatus, code, code, message, List.of(), List.of(), false);
    }

    public boolean delivered() {
        return "MATCHED".equals(status) || "PARTIAL".equals(status);
    }
}
