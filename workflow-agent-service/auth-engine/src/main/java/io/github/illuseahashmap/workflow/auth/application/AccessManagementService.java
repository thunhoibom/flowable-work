package io.github.illuseahashmap.workflow.auth.application;

import io.github.illuseahashmap.workflow.auth.application.dto.AddTenantMemberRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantMemberView;
import java.util.List;

/** Tenant membership of the current tenant. Roles are assigned in EIM, not here. */
public interface AccessManagementService {

    List<TenantMemberView> members(String keyword);

    TenantMemberView addMember(AddTenantMemberRequest request);

    void updateMemberEnabled(String userId, boolean enabled);
}
