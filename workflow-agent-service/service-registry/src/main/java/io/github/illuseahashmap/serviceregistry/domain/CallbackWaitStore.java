package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;
import java.util.Optional;

public interface CallbackWaitStore {

    void insert(CallbackWait wait);

    /** Removes the wait so only one caller can complete it. */
    Optional<CallbackWait> take(String correlationId);

    List<CallbackWait> takeExpired(long nowEpochMs);
}
