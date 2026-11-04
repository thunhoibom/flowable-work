package io.github.illuseahashmap.workflow.auth.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.application.dto.AddTenantMemberRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantMemberView;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository.TenantMember;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository.TenantMembership;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AccessManagementServiceImplTest {

    private final AuthUserRepository userRepository = mock(AuthUserRepository.class);
    private final AuthMembershipRepository membershipRepository = mock(AuthMembershipRepository.class);
    private final CurrentPrincipalProvider principalProvider = () -> new CurrentPrincipal(
            "USER", "admin-id", "admin", "Admin", "tenant-a", Set.of("WF_PLATFORM_ADMIN"), Set.of());
    private final AccessManagementServiceImpl service = new AccessManagementServiceImpl(
            userRepository, membershipRepository, principalProvider);

    private final AuthUser bob = new AuthUser(2L, "user-2", "bob", "Bob", "{sso}", "default", true,
            OffsetDateTime.now(), OffsetDateTime.now());

    @Test
    void addsSignedInUserToCurrentTenant() {
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(membershipRepository.find("user-2", "tenant-a")).thenReturn(Optional.empty());
        when(membershipRepository.findMembers("tenant-a", "bob")).thenReturn(List.of(member(true)));

        TenantMemberView view = service.addMember(new AddTenantMemberRequest(" Bob "));

        verify(membershipRepository).add("user-2", "tenant-a");
        assertThat(view.username()).isEqualTo("bob");
    }

    @Test
    void reEnablesDisabledMembership() {
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(membershipRepository.find("user-2", "tenant-a")).thenReturn(Optional.of(membership(false)));
        when(membershipRepository.findMembers("tenant-a", "bob")).thenReturn(List.of(member(true)));

        service.addMember(new AddTenantMemberRequest("bob"));

        verify(membershipRepository).updateEnabled("user-2", "tenant-a", true);
        verify(membershipRepository, never()).add(anyString(), anyString());
    }

    @Test
    void userMustHaveSignedInBeforeBeingAdded() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(new AddTenantMemberRequest("ghost")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void cannotDisableOwnMembership() {
        assertThatThrownBy(() -> service.updateMemberEnabled("admin-id", false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Cannot disable the current membership");
        verify(membershipRepository, never()).updateEnabled(anyString(), anyString(), anyBoolean());
    }

    @Test
    void disablesAnotherMember() {
        when(membershipRepository.find("user-2", "tenant-a")).thenReturn(Optional.of(membership(true)));

        service.updateMemberEnabled("user-2", false);

        verify(membershipRepository).updateEnabled("user-2", "tenant-a", false);
    }

    private static TenantMembership membership(boolean enabled) {
        return new TenantMembership("user-2", "t-a", "tenant-a", "Tenant A", enabled, true, OffsetDateTime.now());
    }

    private static TenantMember member(boolean enabled) {
        return new TenantMember("user-2", "bob", "Bob", true, enabled, OffsetDateTime.now());
    }
}
