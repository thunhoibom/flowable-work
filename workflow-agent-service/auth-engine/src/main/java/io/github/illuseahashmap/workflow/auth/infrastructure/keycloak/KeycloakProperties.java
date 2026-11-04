package io.github.illuseahashmap.workflow.auth.infrastructure.keycloak;

import io.github.illuseahashmap.workflow.auth.application.port.ExternalProvisioningPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Keycloak (SSO) integration, the only way users sign in. Requests carry the Keycloak access
 * token; user roles come from its {@code eim_roles} claim (assigned in EIM). Only tenant
 * membership is kept locally.
 */
@ConfigurationProperties(prefix = "workflow.auth.keycloak")
public class KeycloakProperties implements ExternalProvisioningPolicy {

    private String serverUrl;
    private String realm;
    private String clientId;
    private String clientSecret;
    /** Defaults to {@code {serverUrl}/realms/{realm}}. */
    private String issuerUri;
    /** Defaults to {@code {issuerUri}/protocol/openid-connect/certs}. */
    private String jwkSetUri;
    /** Accepted {@code azp} values; empty accepts tokens issued to any client of the realm. */
    private List<String> allowedClientIds = new ArrayList<>();
    private String usernameClaim = "preferred_username";
    private String displayNameClaim = "name";
    /** Comma-separated EIM role codes, as in rsa-core-service. */
    private String rolesClaim = "eim_roles";
    private boolean autoProvision = true;
    private String defaultTenantCode = "default";
    private String refreshCookieName = "workflow-agent.refresh-token";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);

    public String resolvedIssuerUri() {
        if (StringUtils.hasText(issuerUri)) {
            return trimTrailingSlash(issuerUri);
        }
        return realmUri();
    }

    /** {@code {serverUrl}/realms/{realm}}; also the base of the EIM endpoints. */
    public String realmUri() {
        return trimTrailingSlash(serverUrl) + "/realms/" + realm;
    }

    public String resolvedJwkSetUri() {
        if (StringUtils.hasText(jwkSetUri)) {
            return jwkSetUri;
        }
        return resolvedIssuerUri() + "/protocol/openid-connect/certs";
    }

    public String tokenEndpoint() {
        return resolvedIssuerUri() + "/protocol/openid-connect/token";
    }

    public String logoutEndpoint() {
        return resolvedIssuerUri() + "/protocol/openid-connect/logout";
    }

    void validate() {
        if (!StringUtils.hasText(serverUrl) || !StringUtils.hasText(realm)) {
            throw new IllegalStateException("workflow.auth.keycloak.server-url and realm are required");
        }
        if (!StringUtils.hasText(clientId)) {
            throw new IllegalStateException("workflow.auth.keycloak.client-id is required");
        }
        if (!StringUtils.hasText(rolesClaim)) {
            throw new IllegalStateException("workflow.auth.keycloak.roles-claim is required");
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public boolean autoProvision() {
        return autoProvision;
    }

    @Override
    public String defaultTenantCode() {
        return defaultTenantCode;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public List<String> getAllowedClientIds() {
        return allowedClientIds;
    }

    public void setAllowedClientIds(List<String> allowedClientIds) {
        this.allowedClientIds = allowedClientIds;
    }

    public String getUsernameClaim() {
        return usernameClaim;
    }

    public void setUsernameClaim(String usernameClaim) {
        this.usernameClaim = usernameClaim;
    }

    public String getDisplayNameClaim() {
        return displayNameClaim;
    }

    public void setDisplayNameClaim(String displayNameClaim) {
        this.displayNameClaim = displayNameClaim;
    }

    public String getRolesClaim() {
        return rolesClaim;
    }

    public void setRolesClaim(String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    public void setAutoProvision(boolean autoProvision) {
        this.autoProvision = autoProvision;
    }

    public void setDefaultTenantCode(String defaultTenantCode) {
        this.defaultTenantCode = defaultTenantCode;
    }

    public String getRefreshCookieName() {
        return refreshCookieName;
    }

    public void setRefreshCookieName(String refreshCookieName) {
        this.refreshCookieName = refreshCookieName;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
