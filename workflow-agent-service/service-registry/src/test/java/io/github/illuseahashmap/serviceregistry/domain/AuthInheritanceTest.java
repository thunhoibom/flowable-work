package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuthInheritanceTest {

    @Test
    void selectsSharedOperationOrNoAuth() {
        AuthConfig shared = new AuthConfig(AuthType.BEARER, null, "shared-token");
        AuthConfig own = new AuthConfig(AuthType.API_KEY, "X-Api-Key", "op-token");
        assertEquals(shared, AuthInheritance.resolve(AuthMode.INHERIT, shared, own));
        assertEquals(own, AuthInheritance.resolve(AuthMode.OVERRIDE, shared, own));
        // NONE drops both the shared secret and the operation secret.
        assertEquals(new AuthConfig(AuthType.NONE, null, null), AuthInheritance.resolve(AuthMode.NONE, shared, own));
    }
}
