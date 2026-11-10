package io.github.illuseahashmap.workflow.eventregistry.interfaces.rest;

import io.github.illuseahashmap.workflow.eventregistry.application.RestChannelService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.RestChannelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveRestChannelRequest;
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

/** The tenant's inbound REST channels. */
@RestController
@RequestMapping("/workflow/management/event-registry/channels")
public class RestChannelController {

    private static final String READ =
            "hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')";
    private static final String WRITE = "hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')";

    private final RestChannelService channels;

    public RestChannelController(RestChannelService channels) {
        this.channels = channels;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<RestChannelView>> list() {
        return ApiResponse.ok(channels.list());
    }

    @GetMapping("/{key}")
    @PreAuthorize(READ)
    public ApiResponse<RestChannelView> get(@PathVariable String key) {
        return ApiResponse.ok(channels.get(key));
    }

    @PutMapping("/{key}/draft")
    @PreAuthorize(WRITE)
    public ApiResponse<RestChannelView> saveDraft(@PathVariable String key,
                                                  @Valid @RequestBody SaveRestChannelRequest request) {
        return ApiResponse.ok(channels.saveDraft(key, request));
    }

    @PostMapping("/{key}/publish")
    @PreAuthorize(WRITE)
    public ApiResponse<RestChannelView> publish(@PathVariable String key, @RequestParam long revision) {
        return ApiResponse.ok(channels.publish(key, revision));
    }

    @DeleteMapping("/{key}")
    @PreAuthorize(WRITE)
    public ApiResponse<Void> delete(@PathVariable String key) {
        channels.delete(key);
        return ApiResponse.ok();
    }
}
