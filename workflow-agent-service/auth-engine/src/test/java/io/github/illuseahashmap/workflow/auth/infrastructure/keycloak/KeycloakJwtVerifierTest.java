package io.github.illuseahashmap.workflow.auth.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier.ExternalIdentity;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class KeycloakJwtVerifierTest {

    private static final String ISSUER = "https://sso.example.test/realms/rsa";
    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();
    }

    @Test
    void extractsNormalizedUsernameAndDisplayName() throws Exception {
        KeycloakJwtVerifier verifier = verifier(List.of());

        ExternalIdentity identity = verifier.verify(token(keyPair, ISSUER, "VETC.RSA", "Alice.Nguyen", 300));

        assertThat(identity.username()).isEqualTo("alice.nguyen");
        assertThat(identity.displayName()).isEqualTo("Alice Nguyen");
        assertThat(identity.subject()).isEqualTo("sub-1");
    }

    @Test
    void rejectsTokenSignedByAnotherKey() throws Exception {
        KeycloakJwtVerifier verifier = verifier(List.of());

        assertThatThrownBy(() -> verifier.verify(token(otherKeyPair, ISSUER, "VETC.RSA", "alice", 300)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid bearer token");
    }

    @Test
    void rejectsTokenFromAnotherIssuer() throws Exception {
        KeycloakJwtVerifier verifier = verifier(List.of());

        assertThatThrownBy(() -> verifier.verify(
                token(keyPair, "https://sso.example.test/realms/other", "VETC.RSA", "alice", 300)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        KeycloakJwtVerifier verifier = verifier(List.of());

        assertThatThrownBy(() -> verifier.verify(token(keyPair, ISSUER, "VETC.RSA", "alice", -600)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Bearer token expired");
    }

    @Test
    void rejectsClientOutsideAllowList() throws Exception {
        KeycloakJwtVerifier verifier = verifier(List.of("VETC.RSA.WORKFLOW"));

        assertThatThrownBy(() -> verifier.verify(token(keyPair, ISSUER, "VETC.RSA.DRIVER", "alice", 300)))
                .isInstanceOf(BusinessException.class);
        assertThat(verifier.verify(token(keyPair, ISSUER, "VETC.RSA.WORKFLOW", "alice", 300)).username())
                .isEqualTo("alice");
    }

    @Test
    void readsEimRolesAndTransactionIdLikeRsaCoreService() throws Exception {
        KeycloakJwtVerifier verifier = verifier(List.of());

        ExternalIdentity identity = verifier.verify(token(keyPair, ISSUER, "VETC.WORKFLOW", "alice", 300));

        assertThat(identity.roles()).containsExactlyInAnyOrder("WF_ADMIN", "WF_VIEWER");
        assertThat(identity.transactionId()).isEqualTo("tx-1");
    }

    @Test
    void parsesCommaSeparatedRoles() {
        assertThat(KeycloakJwtVerifier.roles(" WF_ADMIN, ,WF_VIEWER ")).containsExactlyInAnyOrder("WF_ADMIN", "WF_VIEWER");
        assertThat(KeycloakJwtVerifier.roles(null)).isEmpty();
        assertThat(KeycloakJwtVerifier.roles("")).isEmpty();
    }

    private static KeycloakJwtVerifier verifier(List<String> allowedClientIds) {
        KeycloakProperties properties = new KeycloakProperties();
        properties.setServerUrl("https://sso.example");
        properties.setRealm("rsa");
        properties.setIssuerUri(ISSUER);
        properties.setClientId("VETC.RSA.WORKFLOW");
        properties.setAllowedClientIds(allowedClientIds);
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        return new KeycloakJwtVerifier(properties, KeycloakJwtVerifier.withValidators(decoder, properties));
    }

    private static String token(KeyPair signingKey, String issuer, String azp, String username,
                                long expiresInSeconds) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("sub-1")
                .claim("azp", azp)
                .claim("preferred_username", username)
                .claim("name", "Alice Nguyen")
                .claim("eim_roles", "WF_ADMIN,WF_VIEWER")
                .claim("transaction_id", "tx-1")
                .issueTime(Date.from(now.minusSeconds(Math.max(0, -expiresInSeconds) + 60)))
                .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(signingKey.getPrivate()));
        return jwt.serialize();
    }
}
