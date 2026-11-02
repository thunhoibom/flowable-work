package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

/**
 * Editable service. Description, base URLs, and shared auth stay on the draft
 * until the next publish copies them into {@link PublishedService}.
 */
public record ServiceDraft(
        String serviceKey,
        String name,
        String description,
        List<EnvironmentBaseUrl> baseUrls,
        AuthConfig sharedAuth,
        List<PublishedOperation> operations
) {
    public ServiceDraft {
        description = description == null ? "" : description;
        baseUrls = baseUrls == null ? List.of() : List.copyOf(baseUrls);
        operations = operations == null ? List.of() : List.copyOf(operations);
        if (sharedAuth == null) {
            sharedAuth = new AuthConfig(AuthType.NONE, null, null);
        }
    }

    /** Older callers only know the key, name, and operations. */
    public ServiceDraft(String serviceKey, String name, List<PublishedOperation> operations) {
        this(serviceKey, name, "", List.of(), new AuthConfig(AuthType.NONE, null, null), operations);
    }
}
