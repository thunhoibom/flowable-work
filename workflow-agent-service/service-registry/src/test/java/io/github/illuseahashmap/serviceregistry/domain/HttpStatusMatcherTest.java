package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpStatusMatcherTest {

    @Test
    void matchesExactClassAndRange() {
        // Exact code, status class, and inclusive range are the three pattern forms.
        assertTrue(HttpStatusMatcher.matches(409, List.of("409")));
        assertTrue(HttpStatusMatcher.matches(503, List.of("5xx")));
        assertTrue(HttpStatusMatcher.matches(404, List.of("400-499")));
        assertFalse(HttpStatusMatcher.matches(500, List.of("400-499")));
        assertFalse(HttpStatusMatcher.matches(200, List.of("5xx")));
    }
}
