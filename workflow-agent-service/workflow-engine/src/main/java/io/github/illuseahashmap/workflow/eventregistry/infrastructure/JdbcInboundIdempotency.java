package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.eventregistry.application.port.InboundIdempotency;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Idempotency keys of inbound channel events, kept in {@code platform_inbox_event} under
 * {@code consumer_name = event-channel:<tenantId>:<channelKey>}. Each statement commits on its own.
 */
@Repository
public class JdbcInboundIdempotency implements InboundIdempotency {

    static final String CONSUMER_PREFIX = "event-channel:";
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;

    public JdbcInboundIdempotency(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claim(String consumer, UUID eventId, String tenantCode, Duration lease) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int inserted = jdbc.update("""
                    INSERT INTO platform_inbox_event (event_id, consumer_name, tenant_code, received_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (consumer_name, event_id) DO NOTHING
                    """, eventId, consumer, tenantCode);
            if (inserted == 1) {
                return Claim.CLAIMED;
            }
            Boolean completed = jdbc.query("""
                    SELECT completed_at IS NOT NULL FROM platform_inbox_event
                    WHERE consumer_name = ? AND event_id = ?
                    """, rs -> rs.next() ? rs.getBoolean(1) : null, consumer, eventId);
            if (completed == null) {
                continue; // released between the two statements: try to insert again
            }
            if (completed) {
                return Claim.DUPLICATE;
            }
            int taken = jdbc.update("""
                    UPDATE platform_inbox_event SET received_at = CURRENT_TIMESTAMP
                    WHERE consumer_name = ? AND event_id = ? AND completed_at IS NULL
                      AND received_at <= CURRENT_TIMESTAMP - make_interval(secs => ?)
                    """, consumer, eventId, (double) lease.toMillis() / 1000);
            return taken == 1 ? Claim.CLAIMED : Claim.IN_PROGRESS;
        }
        return Claim.IN_PROGRESS;
    }

    @Override
    public void complete(String consumer, UUID eventId) {
        jdbc.update("UPDATE platform_inbox_event SET completed_at = CURRENT_TIMESTAMP "
                + "WHERE consumer_name = ? AND event_id = ? AND completed_at IS NULL", consumer, eventId);
    }

    @Override
    public void release(String consumer, UUID eventId) {
        jdbc.update("DELETE FROM platform_inbox_event "
                + "WHERE consumer_name = ? AND event_id = ? AND completed_at IS NULL", consumer, eventId);
    }

    @Override
    public int purgeCompletedBefore(Duration retention) {
        return jdbc.update("""
                DELETE FROM platform_inbox_event
                WHERE consumer_name LIKE ? AND completed_at IS NOT NULL
                  AND completed_at < CURRENT_TIMESTAMP - make_interval(secs => ?)
                """, CONSUMER_PREFIX + "%", (double) retention.toMillis() / 1000);
    }
}
