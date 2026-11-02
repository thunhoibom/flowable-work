package io.github.illuseahashmap.serviceregistry.domain;

/** How an operation turns its inputs into an HTTP body. */
public enum ContentType {
    JSON,
    FORM,
    MULTIPART,
    RAW
}
