package io.github.illuseahashmap.workflow.auth.application.port;

import io.github.illuseahashmap.workflow.auth.application.port.ExternalTokenVerifier.ExternalIdentity;
import java.util.List;

/** The signed-in user's menu tree, managed in EIM. */
public interface MenuSitemapProvider {

    /**
     * Active menu entries the user may see, ordered.
     *
     * @throws io.github.illuseahashmap.workflow.shared.exception.BusinessException with
     *         {@code EIM_UNAVAILABLE} when EIM cannot be reached or rejects the request
     */
    List<SitemapEntry> sitemap(ExternalIdentity identity);

    record SitemapEntry(String code, String name, String url, int order, List<String> privileges,
                        List<SitemapEntry> children) {

        public SitemapEntry {
            privileges = List.copyOf(privileges);
            children = List.copyOf(children);
        }
    }
}
