package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RequestUrlTest {

    @Test
    void keepsAbsoluteUrlsAndJoinsRelativePaths() {
        // Absolute operations ignore the environment base URL, including a null base.
        assertEquals("https://crm.example/orders", RequestUrl.join(null, "https://crm.example/orders"));
        assertEquals("http://crm.example/orders", RequestUrl.join("https://ignored", "http://crm.example/orders"));
        assertEquals("https://crm.example/orders", RequestUrl.join("https://crm.example/", "/orders"));
        assertEquals("https://crm.example/orders", RequestUrl.join("https://crm.example", "orders"));
        // A relative path cannot be called until the active environment has a base URL.
        assertThrows(InvalidServiceRequestException.class, () -> RequestUrl.join(" ", "/orders"));
    }
}
