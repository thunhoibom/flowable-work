package io.github.illuseahashmap.workflow.eventregistry.application;

import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventUsagesView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventWaitView;
import io.github.illuseahashmap.workflow.shared.response.PageResult;

/** Read side of event waits for operators (spec 2026-11-09-event-registry-d1-design, slice 6). */
public interface EventWaitQueryService {

    /** Executions of the tenant waiting for {@code eventKey}, newest first. {@code size} is capped at 100. */
    PageResult<EventWaitView> waits(String eventKey, int page, int size);

    EventUsagesView usages(String eventKey);
}
