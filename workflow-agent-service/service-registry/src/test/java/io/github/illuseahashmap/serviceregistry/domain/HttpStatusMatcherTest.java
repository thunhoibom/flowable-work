package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpStatusMatcherTest {

    @Test
    void matchesExactCodeAndClassPattern() {
        assertTrue(HttpStatusMatcher.matches(409, List.of("400", "409", "5XX")));
        assertTrue(HttpStatusMatcher.matches(503, List.of("400", "409", "5XX")));
        assertFalse(HttpStatusMatcher.matches(404, List.of("400", "409", "5XX")));
    }
}
