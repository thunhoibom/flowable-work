package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

/**
 * One BPMN error code and the HTTP statuses that should raise it.
 * The first matching rule wins; later rules are not consulted.
 */
public record ErrorRule(List<String> patterns, String bpmnErrorCode) {

    public ErrorRule {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
    }
}
