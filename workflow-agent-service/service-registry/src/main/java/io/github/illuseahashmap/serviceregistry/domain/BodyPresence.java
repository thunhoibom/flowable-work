package io.github.illuseahashmap.serviceregistry.domain;

/**
 * Whether the call sends a body.
 * An empty body is still a body. {@link #NONE} omits it.
 */
public enum BodyPresence {
    CONTENT,
    EMPTY,
    NONE
}
