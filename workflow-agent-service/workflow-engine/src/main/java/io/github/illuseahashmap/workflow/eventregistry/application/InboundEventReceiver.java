package io.github.illuseahashmap.workflow.eventregistry.application;

import io.github.illuseahashmap.workflow.eventregistry.application.dto.InboundEventResult;
import java.util.Map;

/** Hands an event posted to a REST channel to the waiting process instances of the caller's tenant. */
public interface InboundEventReceiver {

    InboundEventResult receive(String channelKey, Map<String, String> headers, byte[] body, String idempotencyKey);
}
