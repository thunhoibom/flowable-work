package io.github.illuseahashmap.workflow.eventregistry.interfaces.rest;

import io.github.illuseahashmap.workflow.eventregistry.application.EventModelService;
import io.github.illuseahashmap.workflow.eventregistry.application.EventWaitQueryService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventUsagesView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventModelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveEventModelRequest;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The tenant's event model library. */
@RestController
@RequestMapping("/workflow/management/event-registry/events")
public class EventModelController {

    private static final String READ =
            "hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')";
    private static final String WRITE = "hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')";

    private final EventModelService events;
    private final EventWaitQueryService waits;

    public EventModelController(EventModelService events, EventWaitQueryService waits) {
        this.waits = waits;
        this.events = events;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<EventModelView>> list() {
        return ApiResponse.ok(events.list());
    }

    @GetMapping("/{key}")
    @PreAuthorize(READ)
    public ApiResponse<EventModelView> get(@PathVariable String key) {
        return ApiResponse.ok(events.get(key));
    }

    @GetMapping("/{key}/usages")
    @PreAuthorize(READ)
    public ApiResponse<EventUsagesView> usages(@PathVariable String key) {
        return ApiResponse.ok(waits.usages(key));
    }

    @PutMapping("/{key}/draft")
    @PreAuthorize(WRITE)
    public ApiResponse<EventModelView> saveDraft(@PathVariable String key,
                                                 @Valid @RequestBody SaveEventModelRequest request) {
        return ApiResponse.ok(events.saveDraft(key, request));
    }

    @PostMapping("/{key}/publish")
    @PreAuthorize(WRITE)
    public ApiResponse<EventModelView> publish(@PathVariable String key, @RequestParam long revision) {
        return ApiResponse.ok(events.publish(key, revision));
    }

    @DeleteMapping("/{key}")
    @PreAuthorize(WRITE)
    public ApiResponse<Void> delete(@PathVariable String key) {
        events.delete(key);
        return ApiResponse.ok();
    }
}
