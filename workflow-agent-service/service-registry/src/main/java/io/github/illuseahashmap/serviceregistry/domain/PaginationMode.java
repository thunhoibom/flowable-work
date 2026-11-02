package io.github.illuseahashmap.serviceregistry.domain;

/** How the next page is chosen. NONE keeps the historical single request. */
public enum PaginationMode {
    NONE,
    NEXT_URL,
    NEXT_TOKEN
}
