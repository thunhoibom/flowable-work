package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

/**
 * Immutable copy of a service at publish time.
 * Runtime reads this snapshot, so later draft edits do not change a running process.
 */
public record PublishedService(
        String serviceKey,
        String name,
        String description,
        List<EnvironmentBaseUrl> baseUrls,
        AuthConfig sharedAuth,
        List<PublishedOperation> operations
) {
    public PublishedService {
        baseUrls = baseUrls == null ? List.of() : List.copyOf(baseUrls);
        operations = operations == null ? List.of() : List.copyOf(operations);
        if (sharedAuth == null) {
            sharedAuth = new AuthConfig(AuthType.NONE, null, null);
        }
    }
}
