package io.github.illuseahashmap.workflow.auth.infrastructure.eim;

import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier.ExternalIdentity;
import io.github.illuseahashmap.workflow.auth.application.port.MenuSitemapProvider;
import io.github.illuseahashmap.workflow.auth.infrastructure.eim.EimSitemapClient.EimFetchException;
import io.github.illuseahashmap.workflow.auth.infrastructure.eim.EimSitemapClient.Outcome;
import io.github.illuseahashmap.workflow.auth.infrastructure.keycloak.KeycloakProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Loads the user's menu from EIM on each call, like rsa-core-service's {@code /auth/sitemaps}.
 * Inactive nodes ({@code strStatus != "1"}) are dropped with their subtree; siblings are ordered
 * by {@code ord}. API authorization does not depend on this: it uses the token's {@code eim_roles}.
 */
@Component
public class EimSitemapProvider implements MenuSitemapProvider {

    private static final Logger log = LoggerFactory.getLogger(EimSitemapProvider.class);
    static final String METRIC = "workflow_eim_sitemap_requests";

    private final EimSitemapClient client;
    private final String appCode;
    private final MeterRegistry meterRegistry;

    @Autowired
    public EimSitemapProvider(EimProperties properties,
                              KeycloakProperties keycloakProperties,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this(new EimSitemapClient(restClient(properties), sitemapUri(keycloakProperties, properties),
                        requireAppCode(properties)),
                properties.getAppCode(),
                meterRegistry.getIfAvailable());
    }

    EimSitemapProvider(EimSitemapClient client, String appCode, MeterRegistry meterRegistry) {
        this.client = client;
        this.appCode = appCode;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<SitemapEntry> sitemap(ExternalIdentity identity) {
        if (!StringUtils.hasText(identity.transactionId())) {
            log.error("Keycloak token of user {} has no transaction_id claim; EIM sitemap cannot be requested. "
                    + "Check the client mappers of {}", identity.username(), appCode);
            record(Outcome.MISSING_TRANSACTION_ID);
            throw unavailable();
        }
        List<SitemapNode> nodes;
        try {
            nodes = client.fetch(identity.accessToken(), identity.transactionId());
        } catch (EimFetchException exception) {
            log.error("EIM sitemap unavailable for user {} ({}): {}", identity.username(),
                    exception.outcome().tag(), exception.getMessage());
            record(exception.outcome());
            throw unavailable();
        }
        record(Outcome.SUCCESS);
        return entries(nodes);
    }

    static List<SitemapEntry> entries(List<SitemapNode> nodes) {
        return nodes.stream()
                .filter(SitemapNode::active)
                .sorted(Comparator.comparingInt(SitemapNode::order))
                .map(node -> new SitemapEntry(node.code(), node.name(), node.url(), node.order(),
                        node.privileges(), entries(node.children())))
                .toList();
    }

    private void record(Outcome outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC, "outcome", outcome.tag()).increment();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.EIM_UNAVAILABLE, ErrorCode.EIM_UNAVAILABLE.defaultMessage());
    }

    private static String requireAppCode(EimProperties properties) {
        if (!StringUtils.hasText(properties.getAppCode()) || !StringUtils.hasText(properties.getSitemapPath())) {
            throw new IllegalStateException("workflow.auth.eim.app-code and sitemap-path are required");
        }
        return properties.getAppCode();
    }

    private static RestClient restClient(EimProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private static String sitemapUri(KeycloakProperties keycloak, EimProperties eim) {
        requireAppCode(eim);
        String path = eim.getSitemapPath().startsWith("/") ? eim.getSitemapPath() : "/" + eim.getSitemapPath();
        return keycloak.realmUri() + path;
    }
}
