package io.github.illuseahashmap.workflow.auth.application.impl;

import io.github.illuseahashmap.workflow.auth.application.AuthService;
import io.github.illuseahashmap.workflow.auth.application.dto.CurrentUserResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantOptionResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.UpdateProfileRequest;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    private final AuthUserRepository userRepository;
    private final AuthMembershipRepository membershipRepository;
    private final CurrentPrincipalProvider principalProvider;

    public AuthServiceImpl(AuthUserRepository userRepository,
                           AuthMembershipRepository membershipRepository,
                           CurrentPrincipalProvider principalProvider) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.principalProvider = principalProvider;
    }

    @Override
    public CurrentUserResponse currentUser() {
        CurrentPrincipal principal = principalProvider.current();
        return new CurrentUserResponse(
                principal.principalId(),
                principal.username(),
                principal.displayName(),
                principal.tenantCode(),
                principal.roles());
    }

    @Override
    @Transactional
    public CurrentUserResponse updateProfile(UpdateProfileRequest request) {
        CurrentPrincipal principal = principalProvider.current();
        AuthUser user = userRepository.updateDisplayName(principal.principalId(), request.displayName().trim());
        return new CurrentUserResponse(
                user.userId(), user.username(), user.displayName(), principal.tenantCode(), principal.roles());
    }

    @Override
    public List<TenantOptionResponse> tenants() {
        CurrentPrincipal principal = principalProvider.current();
        return membershipRepository.findByUserId(principal.principalId()).stream()
                .map(membership -> new TenantOptionResponse(
                        membership.tenantId(), membership.tenantCode(), membership.tenantName(),
                        membership.membershipEnabled() && membership.tenantEnabled(),
                        membership.tenantCode().equals(principal.tenantCode())))
                .toList();
    }
}
