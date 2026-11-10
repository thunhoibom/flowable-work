package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import java.util.List;

/** Where an event is used: latest process versions waiting for it, and executions waiting now. */
public record EventUsagesView(List<EventUsageView> usages, long waiting) {

    public EventUsagesView {
        usages = List.copyOf(usages);
    }
}
