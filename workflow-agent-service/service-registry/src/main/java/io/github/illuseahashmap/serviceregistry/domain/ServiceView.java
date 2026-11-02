package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

/**
 * Draft shown by the catalog API. {@code sharedAuth.secret} is for the server only;
 * the HTTP response replaces it with {@code secretPresent}.
 */
public record ServiceView(
        String serviceKey,
        String name,
        String description,
        List<EnvironmentBaseUrl> baseUrls,
        AuthConfig sharedAuth,
        List<OperationView> operations
) {
    public ServiceView {
        description = description == null ? "" : description;
        baseUrls = baseUrls == null ? List.of() : List.copyOf(baseUrls);
        operations = operations == null ? List.of() : List.copyOf(operations);
        if (sharedAuth == null) {
            sharedAuth = new AuthConfig(AuthType.NONE, null, null);
        }
    }
}
