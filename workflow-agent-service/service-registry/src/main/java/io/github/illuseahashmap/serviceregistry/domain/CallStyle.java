package io.github.illuseahashmap.serviceregistry.domain;

/** SYNC finishes in the service task. CALLBACK waits for an inbound webhook. */
public enum CallStyle {
    SYNC,
    CALLBACK
}
