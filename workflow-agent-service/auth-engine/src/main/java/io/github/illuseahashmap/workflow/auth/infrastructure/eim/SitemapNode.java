package io.github.illuseahashmap.workflow.auth.infrastructure.eim;

import java.util.List;

/** One node of the EIM sitemap tree as EIM returns it ({@code strStatus = "1"} means active). */
record SitemapNode(String code, String name, String url, int order, boolean active, List<String> privileges,
                   List<SitemapNode> children) {

    SitemapNode {
        privileges = List.copyOf(privileges);
        children = List.copyOf(children);
    }
}
