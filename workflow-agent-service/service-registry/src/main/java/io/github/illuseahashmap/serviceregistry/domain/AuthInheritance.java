package io.github.illuseahashmap.serviceregistry.domain;

/**
 * Picks the credentials for one operation.
 * The first auth argument is the service snapshot; the second is the operation's own credentials.
 */
public final class AuthInheritance {

    private AuthInheritance() {
    }

    public static AuthConfig resolve(AuthMode mode, AuthConfig shared, AuthConfig operation) {
        AuthMode effective = mode == null ? AuthMode.OVERRIDE : mode;
        return switch (effective) {
            // A published operation keeps the shared secret from the snapshot, not the current draft.
            case INHERIT -> shared;
            case OVERRIDE -> operation;
            // NONE suppresses shared credentials for a public operation on a protected service.
            case NONE -> new AuthConfig(AuthType.NONE, null, null);
        };
    }
}
