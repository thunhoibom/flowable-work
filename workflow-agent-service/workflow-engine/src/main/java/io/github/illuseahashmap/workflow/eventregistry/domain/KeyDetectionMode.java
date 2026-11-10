package io.github.illuseahashmap.workflow.eventregistry.domain;

/** How a channel finds the event key of an incoming message. */
public enum KeyDetectionMode {
    /** Every message is the same event. */
    FIXED,
    /** A top-level field of the JSON body holds the event key. */
    JSON_FIELD,
    /** A JSON Pointer into the body holds the event key. */
    JSON_POINTER
}
