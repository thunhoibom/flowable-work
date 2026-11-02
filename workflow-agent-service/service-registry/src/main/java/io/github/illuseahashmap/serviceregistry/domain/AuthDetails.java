package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

/**
 * Extra fields for one auth structure. Secrets stay null when a save should keep the stored value.
 * Older credentials have none of these fields.
 */
public record AuthDetails(
        String tokenUrl,
        String clientId,
        List<String> scopes,
        String certificatePem,
        String bearerVariable,
        String accessKey,
        String region,
        String awsService,
        String signatureHeader) {

    public AuthDetails {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public static AuthDetails empty() {
        return new AuthDetails(null, null, List.of(), null, null, null, null, null, null);
    }
}
