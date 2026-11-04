package io.github.illuseahashmap.workflow.auth.application.impl;

import io.github.illuseahashmap.workflow.auth.application.ExternalAuthService;
import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.port.AuthenticationAttemptGuard;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalIdentityProvider;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalIdentityProvider.ExternalTokens;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalProvisioningPolicy;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier.ExternalIdentity;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository.TenantMembership;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class ExternalAuthServiceImpl implements ExternalAuthService {

    /**
     * Stored in {@code auth_user.password_hash} for SSO accounts: credentials live in Keycloak and
     * this value is not a password hash, so it can never match a password.
     */
    static final String NO_LOCAL_PASSWORD = "{sso}";
    private static final String LOGIN_OPERATION = "LOGIN";
    private static final String TOKEN_TYPE = "Bearer";

    private final ExternalIdentityProvider identityProvider;
    private final ExternalTokenVerifier tokenVerifier;
    private final ExternalProvisioningPolicy provisioningPolicy;
    private final AuthUserRepository userRepository;
    private final AuthTenantRepository tenantRepository;
    private final AuthMembershipRepository membershipRepository;
    private final AuthenticationAttemptGuard authenticationAttemptGuard;
    private final TransactionTemplate transactionTemplate;

    public ExternalAuthServiceImpl(ExternalIdentityProvider identityProvider,
                                   ExternalTokenVerifier tokenVerifier,
                                   ExternalProvisioningPolicy provisioningPolicy,
                                   AuthUserRepository userRepository,
                                   AuthTenantRepository tenantRepository,
                                   AuthMembershipRepository membershipRepository,
                                   AuthenticationAttemptGuard authenticationAttemptGuard,
                                   PlatformTransactionManager transactionManager) {
        this.identityProvider = identityProvider;
        this.tokenVerifier = tokenVerifier;
        this.provisioningPolicy = provisioningPolicy;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.authenticationAttemptGuard = authenticationAttemptGuard;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ExternalSession login(LoginRequest request, String sourceAddress) {
        String username = request.username().trim().toLowerCase();
        authenticationAttemptGuard.assertAllowed(LOGIN_OPERATION, username, sourceAddress);
        try {
            ExternalTokens tokens = identityProvider.passwordGrant(username, request.password());
            ExternalSession session = openSession(tokens, null);
            authenticationAttemptGuard.recordSuccess(LOGIN_OPERATION, username, sourceAddress);
            return session;
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.UNAUTHORIZED
                    || exception.getErrorCode() == ErrorCode.FORBIDDEN) {
                authenticationAttemptGuard.recordFailure(LOGIN_OPERATION, username, sourceAddress);
            }
            throw exception;
        }
    }

    @Override
    public ExternalSession refresh(String refreshToken, String tenantCode) {
        return openSession(identityProvider.refresh(refreshToken), tenantCode);
    }

    @Override
    public void logout(String refreshToken) {
        identityProvider.logout(refreshToken);
    }

    @Override
    public AuthUser resolveUser(ExternalIdentity identity) {
        Optional<AuthUser> existing = userRepository.findByUsername(identity.username());
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!provisioningPolicy.autoProvision()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "User is not registered in Workflow Agent");
        }
        try {
            return transactionTemplate.execute(status -> provision(identity));
        } catch (DuplicateKeyException concurrentSignIn) {
            return userRepository.findByUsername(identity.username())
                    .orElseThrow(() -> concurrentSignIn);
        }
    }

    /** First sign-in: local account plus membership of the default tenant. Roles stay in EIM. */
    private AuthUser provision(ExternalIdentity identity) {
        String tenantCode = provisioningPolicy.defaultTenantCode();
        AuthTenantRepository.AuthTenant tenant = tenantRepository.findByTenantCode(tenantCode)
                .filter(AuthTenantRepository.AuthTenant::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "Default tenant is unavailable"));
        String displayName = StringUtils.hasText(identity.displayName())
                ? identity.displayName() : identity.username();
        AuthUser user = userRepository.save(
                UUID.randomUUID().toString(),
                identity.username(),
                displayName,
                NO_LOCAL_PASSWORD,
                tenant.tenantCode());
        membershipRepository.add(user.userId(), tenant.tenantCode());
        return user;
    }

    private ExternalSession openSession(ExternalTokens tokens, String requestedTenantCode) {
        ExternalIdentity identity = tokenVerifier.verify(tokens.accessToken());
        AuthUser user = resolveUser(identity);
        if (!user.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "User is disabled");
        }
        TenantMembership membership = selectMembership(user, requestedTenantCode);
        long sessionSeconds = tokens.refreshExpiresIn() > 0 ? tokens.refreshExpiresIn() : tokens.expiresIn();
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                Instant.now().plusSeconds(sessionSeconds), ZoneOffset.UTC);
        AuthTokenResponse session = new AuthTokenResponse(
                TOKEN_TYPE,
                tokens.accessToken(),
                sessionSeconds,
                expiresAt,
                user.userId(),
                user.username(),
                user.displayName(),
                membership.tenantCode(),
                identity.roles());
        return new ExternalSession(session, tokens.refreshToken(), tokens.refreshExpiresIn());
    }

    private TenantMembership selectMembership(AuthUser user, String requestedTenantCode) {
        var memberships = membershipRepository.findByUserId(user.userId()).stream()
                .filter(membership -> membership.tenantEnabled() && membership.membershipEnabled())
                .toList();
        if (StringUtils.hasText(requestedTenantCode)) {
            Optional<TenantMembership> requested = memberships.stream()
                    .filter(membership -> membership.tenantCode().equals(requestedTenantCode.trim()))
                    .findFirst();
            if (requested.isPresent()) {
                return requested.get();
            }
        }
        return memberships.stream()
                .sorted(Comparator.comparing(
                        (TenantMembership membership) -> !membership.tenantCode().equals(user.tenantCode())))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "User has no enabled tenant membership"));
    }
}
