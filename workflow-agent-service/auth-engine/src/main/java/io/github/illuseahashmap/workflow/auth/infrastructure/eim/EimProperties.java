package io.github.illuseahashmap.workflow.auth.infrastructure.eim;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** EIM sitemap (menu) of the signed-in user, as rsa-core-service loads it for its portal. */
@ConfigurationProperties("workflow.auth.eim")
public class EimProperties {

    /** EIM application code; equal to the Keycloak client id of the workflow application. */
    private String appCode = "VETC.WORKFLOW";
    /** Appended to {@code {keycloak.server-url}/realms/{keycloak.realm}}. */
    private String sitemapPath = "/auth/sitemaps-for-role-prl";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);

    public String getAppCode() {
        return appCode;
    }

    public void setAppCode(String appCode) {
        this.appCode = appCode;
    }

    public String getSitemapPath() {
        return sitemapPath;
    }

    public void setSitemapPath(String sitemapPath) {
        this.sitemapPath = sitemapPath;
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
