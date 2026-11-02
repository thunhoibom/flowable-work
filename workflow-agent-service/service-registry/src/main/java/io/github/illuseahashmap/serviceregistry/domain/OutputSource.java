package io.github.illuseahashmap.serviceregistry.domain;

/** Where one output value is read from a successful response. */
public enum OutputSource {
    /** A top-level field of a JSON object. */
    JSON_FIELD,
    /** The response body as text, XML, or base64 when the body is a file. */
    RAW_BODY,
    /** The HTTP status code. */
    STATUS,
    /** One response header. */
    RESPONSE_HEADER
}
