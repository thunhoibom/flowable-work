package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.util.Locale;

/** The kinds of models kept in the event registry library. */
public enum RegistryModelType {
    EVENT, CHANNEL;

    /** Name of the Flowable deployment, e.g. {@code event:paymentReceived}. */
    public String deploymentName(String key) {
        return name().toLowerCase(Locale.ROOT) + ":" + key;
    }
}
