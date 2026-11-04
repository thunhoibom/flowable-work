package io.github.illuseahashmap.workflow.auth.application;

import io.github.illuseahashmap.workflow.auth.application.dto.CurrentUserResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantOptionResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.UpdateProfileRequest;
import java.util.List;

/** The signed-in user's profile and tenants. Sign-in itself is {@link ExternalAuthService} (SSO). */
public interface AuthService {

    CurrentUserResponse currentUser();

    CurrentUserResponse updateProfile(UpdateProfileRequest request);

    List<TenantOptionResponse> tenants();
}
