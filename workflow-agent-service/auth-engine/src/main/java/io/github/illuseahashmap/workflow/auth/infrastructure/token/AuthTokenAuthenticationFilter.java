package io.github.illuseahashmap.workflow.auth.infrastructure.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.auth.application.ExternalAuthService;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier.ExternalIdentity;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.interfaces.security.AuthSecurityFilter;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates browser and API users with their Keycloak access token (cookie or Bearer).
 * Roles are the token's {@code eim_roles}, granted as {@code ROLE_<code>} like rsa-core-service;
 * the tenant comes from the {@code X-Tenant-Code} header and must be one of the user's memberships.
 */
@Component
public class AuthTokenAuthenticationFilter extends OncePerRequestFilter implements AuthSecurityFilter {

    /** Tenant the caller wants to work in; always validated against the caller's memberships. */
    public static final String TENANT_HEADER = "X-Tenant-Code";
    private static final int MAX_TENANT_CODE_LENGTH = 64;
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> PUBLIC_AUTH_ENDPOINTS = Set.of(
            "/auth/csrf",
            "/auth/login",
            "/auth/refresh",
            "/auth/logout"
    );

    private final ObjectMapper objectMapper;
    private final AuthTenantRepository tenantRepository;
    private final AuthMembershipRepository membershipRepository;
    private final AuthTokenProperties tokenProperties;
    private final ExternalTokenVerifier tokenVerifier;
    private final ExternalAuthService externalAuthService;

    public AuthTokenAuthenticationFilter(ObjectMapper objectMapper,
                                         AuthTenantRepository tenantRepository,
                                         AuthMembershipRepository membershipRepository,
                                         AuthTokenProperties tokenProperties,
                                         ExternalTokenVerifier tokenVerifier,
                                         ExternalAuthService externalAuthService) {
        this.objectMapper = objectMapper;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.tokenProperties = tokenProperties;
        this.tokenVerifier = tokenVerifier;
        this.externalAuthService = externalAuthService;
    }

    /** The caller's raw access token (Bearer header first, then the session cookie), or null. */
    public String readToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return readCookieToken(request);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || (authorization == null && PUBLIC_AUTH_ENDPOINTS.contains(request.getRequestURI()))
                || (authorization == null && readCookieToken(request) == null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = readToken(request);
            if (token == null) {
                filterChain.doFilter(request, response);
                return;
            }
            String requestedTenantCode = requestedTenantCode(request);
            AuthenticatedData data = TrustedDataAccessContext.runAsAuthentication(
                    () -> authenticate(token, requestedTenantCode));
            CurrentPrincipal principal = new CurrentPrincipal(
                    "USER",
                    data.user().userId(),
                    data.user().username(),
                    data.user().displayName(),
                    data.tenant().tenantCode(),
                    data.roles(),
                    Set.of());
            CurrentPrincipalContext.set(principal);
            TenantContext.set(new TenantContext.TenantInfo(
                    data.tenant().tenantId(), data.tenant().tenantCode(), data.tenant().tenantName()));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    data.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
            filterChain.doFilter(request, response);
        } catch (BusinessException exception) {
            writeError(response, exception);
        } finally {
            CurrentPrincipalContext.clear();
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private AuthenticatedData authenticate(String token, String requestedTenantCode) {
        ExternalIdentity identity = tokenVerifier.verify(token);
        AuthUser user = externalAuthService.resolveUser(identity);
        if (!user.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "User is disabled");
        }
        String tenantCode = requestedTenantCode != null ? requestedTenantCode : defaultTenantCode(user);
        AuthTenantRepository.AuthTenant tenant = tenantRepository.findByTenantCode(tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "Tenant does not exist"));
        AuthMembershipRepository.TenantMembership membership = membershipRepository
                .find(user.userId(), tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "Tenant membership does not exist"));
        if (!tenant.enabled() || !membership.tenantEnabled() || !membership.membershipEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant access is disabled");
        }
        return new AuthenticatedData(tenant, user, identity.roles());
    }

    /** Home tenant when it is accessible, otherwise the first enabled membership. */
    private String defaultTenantCode(AuthUser user) {
        return membershipRepository.findByUserId(user.userId()).stream()
                .filter(membership -> membership.tenantEnabled() && membership.membershipEnabled())
                .sorted(Comparator.comparing(
                        (AuthMembershipRepository.TenantMembership membership)
                                -> !membership.tenantCode().equals(user.tenantCode())))
                .map(AuthMembershipRepository.TenantMembership::tenantCode)
                .findFirst()
                .orElse(user.tenantCode());
    }

    private static String requestedTenantCode(HttpServletRequest request) {
        String value = request.getHeader(TENANT_HEADER);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String tenantCode = value.trim();
        if (tenantCode.length() > MAX_TENANT_CODE_LENGTH) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant does not exist");
        }
        return tenantCode;
    }

    private record AuthenticatedData(AuthTenantRepository.AuthTenant tenant, AuthUser user, Set<String> roles) {
    }

    private String readCookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (tokenProperties.getCookieName().equals(cookie.getName())
                    && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeError(HttpServletResponse response, BusinessException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(exception.getErrorCode() == ErrorCode.FORBIDDEN
                ? HttpServletResponse.SC_FORBIDDEN
                : HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(
                exception.getErrorCode().code(), exception.getMessage())));
    }
}
