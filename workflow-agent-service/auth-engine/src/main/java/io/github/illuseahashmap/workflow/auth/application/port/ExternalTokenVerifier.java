package io.github.illuseahashmap.workflow.auth.application.port;

import java.util.Set;

/** Verifies access tokens issued by the external identity provider (Keycloak). */
public interface ExternalTokenVerifier {

    ExternalIdentity verify(String token);

    /**
     * Verified caller identity.
     *
     * @param roles         EIM role codes from the token ({@code eim_roles}); the caller's only roles
     * @param accessToken   the raw, verified token (needed to call EIM on the caller's behalf; never log it)
     * @param transactionId the {@code transaction_id} claim EIM expects as {@code TRANS_ID}, if present
     */
    record ExternalIdentity(String subject, String username, String displayName, Set<String> roles,
                            String accessToken, String transactionId) {

        public ExternalIdentity {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }
}
