package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.port.InboundIdempotency;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Forgets completed Idempotency-Keys of inbound channels once their retention has passed. */
@Component
public class InboundIdempotencyJanitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboundIdempotencyJanitor.class);

    private final InboundIdempotency idempotency;
    private final EventRegistryProperties properties;

    public InboundIdempotencyJanitor(InboundIdempotency idempotency, EventRegistryProperties properties) {
        this.idempotency = idempotency;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${workflow.event-registry.idempotency-cleanup-ms:3600000}",
            initialDelayString = "${workflow.event-registry.idempotency-cleanup-ms:3600000}")
    public void purge() {
        int removed = TrustedDataAccessContext.runAsSystemWorker(
                () -> idempotency.purgeCompletedBefore(properties.getIdempotencyRetention()));
        if (removed > 0) {
            LOGGER.info("Removed {} expired inbound event idempotency keys", removed);
        }
    }
}
