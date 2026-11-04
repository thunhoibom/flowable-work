package io.github.illuseahashmap.workflow.auth.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.application.ExternalAuthService.ExternalSession;
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
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository.AuthTenant;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class ExternalAuthServiceImplTest {

    private final ExternalIdentityProvider identityProvider = mock(ExternalIdentityProvider.class);
    private final ExternalTokenVerifier tokenVerifier = mock(ExternalTokenVerifier.class);
    private final ExternalProvisioningPolicy policy = mock(ExternalProvisioningPolicy.class);
    private final AuthUserRepository userRepository = mock(AuthUserRepository.class);
    private final AuthTenantRepository tenantRepository = mock(AuthTenantRepository.class);
    private final AuthMembershipRepository membershipRepository = mock(AuthMembershipRepository.class);
    private final AuthenticationAttemptGuard guard = mock(AuthenticationAttemptGuard.class);

    private ExternalAuthServiceImpl service;

    private final ExternalIdentity identity = new ExternalIdentity(
            "sub-1", "alice", "Alice Nguyen", Set.of("WF_OPERATOR"), "a.b.c", "tx-1");
    private final AuthUser alice = user(true);

    @BeforeEach
    void setUp() {
        when(policy.autoProvision()).thenReturn(true);
        when(policy.defaultTenantCode()).thenReturn("default");
        service = new ExternalAuthServiceImpl(identityProvider, tokenVerifier, policy, userRepository,
                tenantRepository, membershipRepository, guard, mock(PlatformTransactionManager.class));
    }

    @Test
    void resolvesExistingAccountWithoutProvisioning() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

        assertThat(service.resolveUser(identity)).isSameAs(alice);
        verify(userRepository, never()).save(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void provisionsAccountWithDefaultTenantMembershipOnly() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(tenantRepository.findByTenantCode("default"))
                .thenReturn(Optional.of(new AuthTenant("t-default", "default", "Default", true)));
        when(userRepository.save(anyString(), eq("alice"), eq("Alice Nguyen"),
                eq(ExternalAuthServiceImpl.NO_LOCAL_PASSWORD), eq("default"))).thenReturn(alice);

        assertThat(service.resolveUser(identity)).isSameAs(alice);
        verify(membershipRepository).add("u-1", "default");
    }

    @Test
    void rejectsUnknownAccountWhenProvisioningIsDisabled() {
        when(policy.autoProvision()).thenReturn(false);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveUser(identity))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void loginReturnsEimRolesFromTheTokenAndUsesRefreshLifetime() {
        when(identityProvider.passwordGrant("alice", "secret"))
                .thenReturn(new ExternalTokens("a.b.c", 300, "refresh", 1800));
        when(tokenVerifier.verify("a.b.c")).thenReturn(identity);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(membershipRepository.findByUserId("u-1"))
                .thenReturn(List.of(membership("rsa-hn"), membership("default")));

        ExternalSession session = service.login(new LoginRequest(" Alice ", "secret"), "127.0.0.1");

        assertThat(session.session().tenantCode()).isEqualTo("default");
        assertThat(session.session().roles()).containsExactly("WF_OPERATOR");
        assertThat(session.session().expiresIn()).isEqualTo(1800);
        assertThat(session.refreshToken()).isEqualTo("refresh");
        verify(guard).recordSuccess("LOGIN", "alice", "127.0.0.1");
    }

    @Test
    void failedLoginIsRecordedForBruteForceProtection() {
        when(identityProvider.passwordGrant("alice", "wrong"))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid username or password"));

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong"), "127.0.0.1"))
                .isInstanceOf(BusinessException.class);
        verify(guard).recordFailure("LOGIN", "alice", "127.0.0.1");
    }

    @Test
    void disabledLocalAccountCannotSignIn() {
        when(identityProvider.passwordGrant("alice", "secret"))
                .thenReturn(new ExternalTokens("a.b.c", 300, "refresh", 1800));
        when(tokenVerifier.verify("a.b.c")).thenReturn(identity);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "secret"), "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void refreshKeepsRequestedTenantWhenStillAccessible() {
        when(identityProvider.refresh("refresh"))
                .thenReturn(new ExternalTokens("a.b.c", 300, "refresh-2", 1800));
        when(tokenVerifier.verify("a.b.c")).thenReturn(identity);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(membershipRepository.findByUserId("u-1"))
                .thenReturn(List.of(membership("default"), membership("rsa-hn")));

        ExternalSession session = service.refresh("refresh", "rsa-hn");

        assertThat(session.session().tenantCode()).isEqualTo("rsa-hn");
        assertThat(session.refreshToken()).isEqualTo("refresh-2");
    }

    private static AuthUser user(boolean enabled) {
        return new AuthUser(1L, "u-1", "alice", "Alice Nguyen", "{sso}", "default", enabled,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static TenantMembership membership(String tenantCode) {
        return new TenantMembership("u-1", "t-" + tenantCode, tenantCode, tenantCode, true, true,
                OffsetDateTime.now());
    }
}
