package io.github.illuseahashmap.workflow.auth.infrastructure.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.auth.application.ExternalAuthService;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier.ExternalIdentity;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository.TenantMembership;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository.AuthTenant;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AuthTokenAuthenticationFilterTest {

    private static final String TOKEN = "header.payload.signature";

    private final AuthTenantRepository tenantRepository = mock(AuthTenantRepository.class);
    private final AuthMembershipRepository membershipRepository = mock(AuthMembershipRepository.class);
    private final ExternalTokenVerifier tokenVerifier = mock(ExternalTokenVerifier.class);
    private final ExternalAuthService externalAuthService = mock(ExternalAuthService.class);

    private final AuthTokenAuthenticationFilter filter = new AuthTokenAuthenticationFilter(
            new ObjectMapper(),
            tenantRepository,
            membershipRepository,
            new AuthTokenProperties(),
            tokenVerifier,
            externalAuthService);

    private final AuthUser alice = new AuthUser(1L, "u-1", "alice", "Alice", "{sso}", "default", true,
            OffsetDateTime.now(), OffsetDateTime.now());

    @Test
    void ignoresStaleCookieOnPublicBrowserAuthenticationEndpoints() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/refresh");
        request.setCookies(new Cookie("workflow-agent.access-token", TOKEN));

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void authenticatesCookieOnProtectedEndpoints() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
        request.setCookies(new Cookie("workflow-agent.access-token", TOKEN));

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void eimRolesBecomeSpringRolesLikeRsaCoreService() throws Exception {
        givenUser(Set.of("WF_ADMIN", "RSA_ADMIN"));
        givenMembership("default");
        when(membershipRepository.findByUserId("u-1")).thenReturn(List.of(membership("default")));

        AtomicReference<CurrentPrincipal> principal = new AtomicReference<>();
        AtomicReference<List<String>> authorities = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(null), response, (req, res) -> {
            principal.set(CurrentPrincipalContext.current());
            authorities.set(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).toList());
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(principal.get().tenantCode()).isEqualTo("default");
        assertThat(principal.get().roles()).containsExactlyInAnyOrder("WF_ADMIN", "RSA_ADMIN");
        assertThat(principal.get().permissions()).isEmpty();
        assertThat(authorities.get()).containsExactlyInAnyOrder("ROLE_WF_ADMIN", "ROLE_RSA_ADMIN");
    }

    @Test
    void tenantHeaderSelectsTheTenant() throws Exception {
        givenUser(Set.of("WF_VIEWER"));
        givenMembership("rsa-hn");

        AtomicReference<String> tenantInContext = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(" rsa-hn "), response,
                (req, res) -> tenantInContext.set(TenantContext.current().tenantCode()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tenantInContext.get()).isEqualTo("rsa-hn");
    }

    @Test
    void tenantHeaderForNonMemberIsForbidden() throws Exception {
        givenUser(Set.of("WF_PLATFORM_ADMIN"));
        when(tenantRepository.findByTenantCode("other"))
                .thenReturn(Optional.of(new AuthTenant("t-other", "other", "Other", true)));
        when(membershipRepository.find("u-1", "other")).thenReturn(Optional.empty());

        AtomicReference<CurrentPrincipal> principal = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("other"), response, (req, res) -> principal.set(CurrentPrincipalContext.current()));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(principal.get()).isNull();
    }

    @Test
    void invalidTokenIsUnauthorized() throws Exception {
        when(tokenVerifier.verify(TOKEN))
                .thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid bearer token"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(null), response, (req, res) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
    }

    private void givenUser(Set<String> roles) {
        ExternalIdentity identity = new ExternalIdentity("sub-1", "alice", "Alice", roles, TOKEN, "tx-1");
        when(tokenVerifier.verify(TOKEN)).thenReturn(identity);
        when(externalAuthService.resolveUser(identity)).thenReturn(alice);
    }

    private void givenMembership(String tenantCode) {
        when(tenantRepository.findByTenantCode(tenantCode))
                .thenReturn(Optional.of(new AuthTenant("t-" + tenantCode, tenantCode, tenantCode, true)));
        when(membershipRepository.find("u-1", tenantCode)).thenReturn(Optional.of(membership(tenantCode)));
    }

    private static TenantMembership membership(String tenantCode) {
        return new TenantMembership("u-1", "t-" + tenantCode, tenantCode, tenantCode, true, true,
                OffsetDateTime.now());
    }

    private static MockHttpServletRequest request(String tenantHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workflow/definitions");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        if (tenantHeader != null) {
            request.addHeader(AuthTokenAuthenticationFilter.TENANT_HEADER, tenantHeader);
        }
        return request;
    }
}
