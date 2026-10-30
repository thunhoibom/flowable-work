package io.github.illuseahashmap.serviceregistry.domain;

import java.util.Map;

public record CallOutcome(String bpmnErrorCode, Map<String, Object> variables) {

    public boolean failed() {
        return bpmnErrorCode != null;
    }
}
