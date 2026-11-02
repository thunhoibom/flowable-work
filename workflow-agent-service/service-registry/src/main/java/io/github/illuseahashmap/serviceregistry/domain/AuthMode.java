package io.github.illuseahashmap.serviceregistry.domain;

/** How an operation chooses the credentials that are sent. */
public enum AuthMode {
    /** Use the credentials published on the service. */
    INHERIT,
    /** Use the credentials stored on this operation. */
    OVERRIDE,
    /** Send no credentials, even when the service has shared auth. */
    NONE
}
