package io.github.illuseahashmap.workflow.auth.application.impl;

import io.github.illuseahashmap.workflow.auth.application.AccessManagementService;
import io.github.illuseahashmap.workflow.auth.application.dto.AddTenantMemberRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantMemberView;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessManagementServiceImpl implements AccessManagementService {

    private final AuthUserRepository userRepository;
    private final AuthMembershipRepository membershipRepository;
    private final CurrentPrincipalProvider principalProvider;

    public AccessManagementServiceImpl(AuthUserRepository userRepository,
                                       AuthMembershipRepository membershipRepository,
                                       CurrentPrincipalProvider principalProvider) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.principalProvider = principalProvider;
    }

    @Override
    public List<TenantMemberView> members(String keyword) {
        return membershipRepository.findMembers(currentTenantCode(), keyword).stream()
                .map(AccessManagementServiceImpl::toView)
                .toList();
    }

    /** Adds (or re-enables) a user who has already signed in once through SSO. */
    @Override
    @Transactional
    public TenantMemberView addMember(AddTenantMemberRequest request) {
        String tenantCode = currentTenantCode();
        AuthUser user = userRepository.findByUsername(request.username().trim().toLowerCase())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "User does not exist; they must sign in once before being added"));
        membershipRepository.lockTenantMemberships(tenantCode);
        AuthMembershipRepository.TenantMembership existing = membershipRepository
                .find(user.userId(), tenantCode).orElse(null);
        if (existing == null) {
            membershipRepository.add(user.userId(), tenantCode);
        } else if (!existing.membershipEnabled()) {
            membershipRepository.updateEnabled(user.userId(), tenantCode, true);
        }
        return members(user.username()).stream()
                .filter(member -> member.userId().equals(user.userId()))
                .findFirst()
                .orElseThrow();
    }

    @Override
    @Transactional
    public void updateMemberEnabled(String userId, boolean enabled) {
        String tenantCode = currentTenantCode();
        if (principalProvider.current().principalId().equals(userId) && !enabled) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Cannot disable the current membership");
        }
        membershipRepository.find(userId, tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Tenant membership does not exist"));
        membershipRepository.lockTenantMemberships(tenantCode);
        membershipRepository.updateEnabled(userId, tenantCode, enabled);
    }

    private static TenantMemberView toView(AuthMembershipRepository.TenantMember member) {
        return new TenantMemberView(member.userId(), member.username(), member.displayName(),
                member.userEnabled() && member.membershipEnabled(), member.joinedAt());
    }

    private String currentTenantCode() {
        return principalProvider.current().tenantCode();
    }
}
