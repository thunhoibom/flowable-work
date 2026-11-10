package io.github.illuseahashmap.workflow.eventregistry.interfaces.rest;

import io.github.illuseahashmap.workflow.eventregistry.application.EventWaitQueryService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventWaitView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Executions of the tenant waiting for an event: the "Đang chờ" tab. */
@RestController
@RequestMapping("/workflow/management/event-registry/waits")
public class EventWaitController {

    private final EventWaitQueryService waits;

    public EventWaitController(EventWaitQueryService waits) {
        this.waits = waits;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<PageResult<EventWaitView>> list(@RequestParam String eventKey,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(waits.waits(eventKey, page, size));
    }
}
