package io.github.illuseahashmap.workflow.auth.infrastructure.eim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier.ExternalIdentity;
import io.github.illuseahashmap.workflow.auth.application.port.MenuSitemapProvider.SitemapEntry;
import io.github.illuseahashmap.workflow.auth.infrastructure.eim.EimSitemapClient.EimFetchException;
import io.github.illuseahashmap.workflow.auth.infrastructure.eim.EimSitemapClient.Outcome;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EimSitemapProviderTest {

    private final EimSitemapClient client = mock(EimSitemapClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EimSitemapProvider provider = new EimSitemapProvider(client, "VETC.WORKFLOW", meters);
    private final ExternalIdentity identity = new ExternalIdentity(
            "sub", "alice", "Alice", Set.of("WF_ADMIN"), "a.b.c", "tx-1");

    @Test
    void keepsActiveNodesOrderedByOrd() {
        when(client.fetch("a.b.c", "tx-1")).thenReturn(List.of(
                node("WF_ROOT", 1, true,
                        node("WF_INSTANCE", 2, true),
                        node("WF_DEFINITION", 1, true),
                        node("WF_SERVICE", 3, false))));

        List<SitemapEntry> sitemap = provider.sitemap(identity);

        assertThat(sitemap).singleElement().satisfies(root -> {
            assertThat(root.code()).isEqualTo("WF_ROOT");
            assertThat(root.children()).extracting(SitemapEntry::code)
                    .containsExactly("WF_DEFINITION", "WF_INSTANCE");
        });
        assertThat(meters.counter(EimSitemapProvider.METRIC, "outcome", "success").count()).isEqualTo(1);
    }

    @Test
    void inactiveNodeHidesItsSubtree() {
        when(client.fetch("a.b.c", "tx-1")).thenReturn(List.of(
                node("WF_ROOT", 1, false, node("WF_DEFINITION", 1, true))));

        assertThat(provider.sitemap(identity)).isEmpty();
    }

    @Test
    void eimFailureIsReportedAsUnavailable() {
        when(client.fetch(anyString(), anyString()))
                .thenThrow(new EimFetchException(Outcome.SERVER_ERROR, "HTTP 502"));

        assertThatThrownBy(() -> provider.sitemap(identity))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EIM_UNAVAILABLE);
        assertThat(meters.counter(EimSitemapProvider.METRIC, "outcome", "server_error").count()).isEqualTo(1);
    }

    @Test
    void tokenWithoutTransactionIdCannotLoadTheMenu() {
        ExternalIdentity noTransaction = new ExternalIdentity("sub", "alice", "Alice", Set.of(), "a.b.c", null);

        assertThatThrownBy(() -> provider.sitemap(noTransaction))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EIM_UNAVAILABLE);
        verify(client, never()).fetch(anyString(), anyString());
    }

    private static SitemapNode node(String code, int order, boolean active, SitemapNode... children) {
        return new SitemapNode(code, code + " name", "/" + code, order, active, List.of("ACCESS"), List.of(children));
    }
}
