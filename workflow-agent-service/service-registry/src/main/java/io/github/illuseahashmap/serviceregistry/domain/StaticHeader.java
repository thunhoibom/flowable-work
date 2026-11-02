package io.github.illuseahashmap.serviceregistry.domain;

/** A header stored on the operation. It is not read from a process variable. */
public record StaticHeader(String name, String value) {
}
