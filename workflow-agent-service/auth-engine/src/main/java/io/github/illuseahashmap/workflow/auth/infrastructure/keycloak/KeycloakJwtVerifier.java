package io.github.illuseahashmap.workflow.auth.infrastructure.keycloak;

import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Verifies Keycloak access tokens (RS256, keys from the realm JWK set). */
@Component
public class KeycloakJwtVerifier implements ExternalTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(KeycloakJwtVerifier.class);
    /** Session id EIM expects in the {@code TRANS_ID} header of sitemap requests. */
    static final String TRANSACTION_ID_CLAIM = "transaction_id";

    private final KeycloakProperties properties;
    private volatile JwtDecoder decoder;

    @Autowired
    public KeycloakJwtVerifier(KeycloakProperties properties) {
        this.properties = properties;
        properties.validate();
    }

    /** Test hook: use a pre-built decoder instead of fetching the realm JWK set. */
    KeycloakJwtVerifier(KeycloakProperties properties, JwtDecoder decoder) {
        this.properties = properties;
        this.decoder = decoder;
    }

    @Override
    public ExternalIdentity verify(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid bearer token");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(token);
        } catch (JwtValidationException exception) {
            boolean expired = exception.getErrors().stream()
                    .anyMatch(error -> error.getDescription() != null
                            && error.getDescription().toLowerCase().contains("expired"));
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    expired ? "Bearer token expired" : "Invalid bearer token");
        } catch (JwtException exception) {
            log.debug("Rejected Keycloak token: {}", exception.getMessage());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid bearer token");
        }
        String username = jwt.getClaimAsString(properties.getUsernameClaim());
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Bearer token has no username");
        }
        String displayName = jwt.getClaimAsString(properties.getDisplayNameClaim());
        return new ExternalIdentity(
                jwt.getSubject(),
                username.trim().toLowerCase(),
                StringUtils.hasText(displayName) ? displayName.trim() : null,
                roles(jwt.getClaimAsString(properties.getRolesClaim())),
                token,
                jwt.getClaimAsString(TRANSACTION_ID_CLAIM));
    }

    /** {@code "WF_ADMIN, WF_VIEWER"} → {@code [WF_ADMIN, WF_VIEWER]}, the same parsing as rsa-core-service. */
    static Set<String> roles(String claim) {
        if (!StringUtils.hasText(claim)) {
            return Set.of();
        }
        return Arrays.stream(claim.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private JwtDecoder decoder() {
        JwtDecoder current = decoder;
        if (current == null) {
            synchronized (this) {
                current = decoder;
                if (current == null) {
                    current = buildDecoder();
                    decoder = current;
                }
            }
        }
        return current;
    }

    private JwtDecoder buildDecoder() {
        return withValidators(NimbusJwtDecoder.withJwkSetUri(properties.resolvedJwkSetUri()).build(), properties);
    }

    static NimbusJwtDecoder withValidators(NimbusJwtDecoder nimbus, KeycloakProperties properties) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(properties.resolvedIssuerUri()));
        if (!properties.getAllowedClientIds().isEmpty()) {
            validators.add(authorizedPartyValidator(properties.getAllowedClientIds()));
        }
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return nimbus;
    }

    static OAuth2TokenValidator<Jwt> authorizedPartyValidator(List<String> allowedClientIds) {
        return jwt -> {
            String authorizedParty = jwt.getClaimAsString("azp");
            if (authorizedParty != null && allowedClientIds.contains(authorizedParty)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "Token was issued to a client that is not allowed", null));
        };
    }
}
