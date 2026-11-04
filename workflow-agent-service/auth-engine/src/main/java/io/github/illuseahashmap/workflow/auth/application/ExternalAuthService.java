package io.github.illuseahashmap.workflow.auth.application;

import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier.ExternalIdentity;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;

/**
 * Sign-in through the external identity provider (Keycloak SSO), the only way users sign in.
 * Credentials, token lifetimes and roles ({@code eim_roles}) belong to the provider; only
 * tenant membership is local. Callers must run these methods inside
 * {@code TrustedDataAccessContext.runAsAuthentication}.
 */
public interface ExternalAuthService {

    ExternalSession login(LoginRequest request, String sourceAddress);

    /**
     * Exchanges the refresh token for new tokens. {@code tenantCode} is the tenant the browser
     * is working in; the home tenant is used when it is blank or no longer accessible.
     */
    ExternalSession refresh(String refreshToken, String tenantCode);

    void logout(String refreshToken);

    /** Maps a verified identity to the local account, creating it on first sign-in when allowed. */
    AuthUser resolveUser(ExternalIdentity identity);

    record ExternalSession(AuthTokenResponse session, String refreshToken, long refreshExpiresIn) {
    }
}
