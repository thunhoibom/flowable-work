package io.github.illuseahashmap.workflow.auth.infrastructure.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Browser session cookie carrying the Keycloak access token. */
@ConfigurationProperties(prefix = "workflow.auth.token")
public class AuthTokenProperties {

    private String cookieName = "workflow-agent.access-token";
    private boolean cookieSecure = true;
    private String cookieSameSite = "Lax";

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getCookieSameSite() {
        return cookieSameSite;
    }

    public void setCookieSameSite(String cookieSameSite) {
        this.cookieSameSite = cookieSameSite;
    }
}
