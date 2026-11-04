package io.github.illuseahashmap.workflow.auth.interfaces.rest;

import io.github.illuseahashmap.workflow.auth.application.AuthService;
import io.github.illuseahashmap.workflow.auth.application.ExternalAuthService;
import io.github.illuseahashmap.workflow.auth.application.dto.BrowserAuthResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.CurrentUserResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantOptionResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.UpdateProfileRequest;
import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier;
import io.github.illuseahashmap.workflow.auth.application.port.MenuSitemapProvider;
import io.github.illuseahashmap.workflow.auth.application.port.MenuSitemapProvider.SitemapEntry;
import io.github.illuseahashmap.workflow.auth.infrastructure.keycloak.KeycloakProperties;
import io.github.illuseahashmap.workflow.auth.infrastructure.token.AuthTokenAuthenticationFilter;
import io.github.illuseahashmap.workflow.auth.infrastructure.token.AuthTokenProperties;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Browser session over Keycloak SSO (the only sign-in), plus the user's EIM menu. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final AuthTokenProperties tokenProperties;
    private final ExternalAuthService externalAuthService;
    private final KeycloakProperties keycloakProperties;
    private final ExternalTokenVerifier tokenVerifier;
    private final MenuSitemapProvider sitemapProvider;

    public AuthController(AuthService authService,
                          AuthTokenProperties tokenProperties,
                          ExternalAuthService externalAuthService,
                          KeycloakProperties keycloakProperties,
                          ExternalTokenVerifier tokenVerifier,
                          MenuSitemapProvider sitemapProvider) {
        this.authService = authService;
        this.tokenProperties = tokenProperties;
        this.externalAuthService = externalAuthService;
        this.keycloakProperties = keycloakProperties;
        this.tokenVerifier = tokenVerifier;
        this.sitemapProvider = sitemapProvider;
    }

    @PostMapping("/login")
    public ApiResponse<BrowserAuthResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest servletRequest,
                                                HttpServletResponse httpResponse) {
        var session = TrustedDataAccessContext.runAsAuthentication(
                () -> externalAuthService.login(request, servletRequest.getRemoteAddr()));
        writeSession(httpResponse, session);
        return ApiResponse.ok(BrowserAuthResponse.from(session.session()));
    }

    /** Renews the SSO access token from the HttpOnly refresh-token cookie. */
    @PostMapping("/refresh")
    public ApiResponse<BrowserAuthResponse> refresh(HttpServletRequest servletRequest,
                                                  HttpServletResponse httpResponse) {
        String refreshToken = readCookie(servletRequest, keycloakProperties.getRefreshCookieName());
        if (!StringUtils.hasText(refreshToken)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Session expired");
        }
        String tenantCode = servletRequest.getHeader(AuthTokenAuthenticationFilter.TENANT_HEADER);
        try {
            var session = TrustedDataAccessContext.runAsAuthentication(
                    () -> externalAuthService.refresh(refreshToken, tenantCode));
            writeSession(httpResponse, session);
            return ApiResponse.ok(BrowserAuthResponse.from(session.session()));
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.UNAUTHORIZED) {
                clearCookies(httpResponse);
            }
            throw exception;
        }
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> currentUser() {
        return ApiResponse.ok(authService.currentUser());
    }

    @PatchMapping("/me")
    public ApiResponse<CurrentUserResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(authService.updateProfile(request));
    }

    @GetMapping("/tenants")
    public ApiResponse<List<TenantOptionResponse>> tenants() {
        return ApiResponse.ok(authService.tenants());
    }

    /** The signed-in user's menu from EIM (like rsa-core-service {@code GET /auth/sitemaps}). */
    @GetMapping("/sitemaps")
    public ApiResponse<List<SitemapEntry>> sitemaps(HttpServletRequest request) {
        String token = readAccessToken(request);
        if (token == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Unauthorized request");
        }
        return ApiResponse.ok(sitemapProvider.sitemap(tokenVerifier.verify(token)));
    }

    @GetMapping("/csrf")
    public ApiResponse<String> csrf(CsrfToken token) {
        return ApiResponse.ok(token.getToken());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        externalAuthService.logout(readCookie(request, keycloakProperties.getRefreshCookieName()));
        clearCookies(response);
        return ApiResponse.ok(null);
    }

    private void writeSession(HttpServletResponse response, ExternalAuthService.ExternalSession session) {
        // The access cookie lives as long as the SSO session, so an expired JWT reaches the
        // filter (401) and the browser can refresh it; the JWT exp claim bounds its validity.
        writeCookie(response, tokenProperties.getCookieName(), session.session().accessToken(),
                session.session().expiresIn());
        if (StringUtils.hasText(session.refreshToken())) {
            long refreshSeconds = session.refreshExpiresIn() > 0
                    ? session.refreshExpiresIn() : session.session().expiresIn();
            writeCookie(response, keycloakProperties.getRefreshCookieName(), session.refreshToken(), refreshSeconds);
        }
    }

    private void clearCookies(HttpServletResponse response) {
        writeCookie(response, tokenProperties.getCookieName(), "", 0);
        writeCookie(response, keycloakProperties.getRefreshCookieName(), "", 0);
    }

    private void writeCookie(HttpServletResponse response, String name, String value, long maxAgeSeconds) {
        response.addHeader("Set-Cookie", ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(tokenProperties.isCookieSecure())
                .sameSite(tokenProperties.getCookieSameSite())
                .path("/")
                .maxAge(maxAgeSeconds)
                .build()
                .toString());
    }

    private String readAccessToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return readCookie(request, tokenProperties.getCookieName());
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
