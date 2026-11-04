package io.github.illuseahashmap.workflow.auth.application.port;

/**
 * External identity provider (for example Keycloak) that owns user credentials.
 * Browser login, refresh and logout are delegated to it.
 */
public interface ExternalIdentityProvider {

    ExternalTokens passwordGrant(String username, String password);

    ExternalTokens refresh(String refreshToken);

    void logout(String refreshToken);

    record ExternalTokens(
            String accessToken,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn
    ) {
    }
}
