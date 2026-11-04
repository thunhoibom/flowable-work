package io.github.illuseahashmap.workflow.auth.interfaces.rest;

import io.github.illuseahashmap.workflow.auth.application.AccessManagementService;
import io.github.illuseahashmap.workflow.auth.application.dto.AddTenantMemberRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantMemberView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant membership. Roles are assigned in EIM. */
@RestController
@RequestMapping("/auth/access")
@PreAuthorize("hasRole('WF_PLATFORM_ADMIN')")
public class AccessManagementController {

    private final AccessManagementService accessManagementService;

    public AccessManagementController(AccessManagementService accessManagementService) {
        this.accessManagementService = accessManagementService;
    }

    @GetMapping("/members")
    public ApiResponse<List<TenantMemberView>> members(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(accessManagementService.members(keyword));
    }

    @PostMapping("/members")
    public ApiResponse<TenantMemberView> addMember(@Valid @RequestBody AddTenantMemberRequest request) {
        return ApiResponse.ok(accessManagementService.addMember(request));
    }

    @PostMapping("/members/{userId}/enabled")
    public ApiResponse<Void> updateMemberEnabled(@PathVariable String userId,
                                                 @RequestParam boolean enabled) {
        accessManagementService.updateMemberEnabled(userId, enabled);
        return ApiResponse.ok();
    }
}
