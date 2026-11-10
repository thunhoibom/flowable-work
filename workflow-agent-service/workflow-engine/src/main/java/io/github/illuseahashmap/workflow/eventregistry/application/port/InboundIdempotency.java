package io.github.illuseahashmap.workflow.eventregistry.application.port;

import java.time.Duration;
import java.util.UUID;

/** One delivery per Idempotency-Key and channel (spec 2026-11-09-event-registry-d1-design). */
public interface InboundIdempotency {

    enum Claim {
        /** This call may process the event. */
        CLAIMED,
        /** The event was already processed successfully. */
        DUPLICATE,
        /** Another call is processing the event right now. */
        IN_PROGRESS
    }

    /** Takes the key, or takes it over when a previous attempt held it longer than {@code lease}. */
    Claim claim(String consumerName, UUID eventId, String tenantCode, Duration lease);

    /** Keeps the key: later calls with it are duplicates. */
    void complete(String consumerName, UUID eventId);

    /** Frees the key of an unfinished attempt so the caller may retry with it. */
    void release(String consumerName, UUID eventId);

    /** Removes completed channel keys older than {@code retention}. */
    int purgeCompletedBefore(Duration retention);
}
