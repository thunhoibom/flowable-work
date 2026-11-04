package io.github.illuseahashmap.workflow.auth.application;

import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sets up a new tenant's local side: the creating administrator becomes its first member. */
@Service
public class AuthTenantProvisioningService {

    private final AuthMembershipRepository membershipRepository;

    public AuthTenantProvisioningService(AuthMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    @Transactional
    public void provision(String tenantCode, String administratorUserId) {
        membershipRepository.add(administratorUserId, tenantCode);
    }
}
