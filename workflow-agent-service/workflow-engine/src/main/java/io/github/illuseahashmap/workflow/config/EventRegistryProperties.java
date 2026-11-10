package io.github.illuseahashmap.workflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Event registry features. Manual receive tasks never depend on these settings. */
@ConfigurationProperties("workflow.event-registry")
public class EventRegistryProperties {

    /** Whether event models, channels and event waits may be published. */
    private boolean enabled = true;

    /** Largest webhook body accepted, in bytes. */
    private int maxBodyBytes = 262_144;

    /** How long a completed Idempotency-Key keeps blocking duplicates. */
    private Duration idempotencyRetention = Duration.ofDays(7);

    /** How long an unfinished delivery holds its Idempotency-Key before another attempt may take over. */
    private Duration inProgressLease = Duration.ofSeconds(60);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public Duration getIdempotencyRetention() {
        return idempotencyRetention;
    }

    public void setIdempotencyRetention(Duration idempotencyRetention) {
        this.idempotencyRetention = idempotencyRetention;
    }

    public Duration getInProgressLease() {
        return inProgressLease;
    }

    public void setInProgressLease(Duration inProgressLease) {
        this.inProgressLease = inProgressLease;
    }
}
