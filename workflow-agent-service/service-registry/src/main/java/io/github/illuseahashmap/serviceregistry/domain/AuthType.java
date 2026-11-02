package io.github.illuseahashmap.serviceregistry.domain;

public enum AuthType {
    NONE,
    BEARER,
    BASIC,
    API_KEY,
    /** Token URL, client id, client secret, and scopes. The access token is fetched at call time. */
    OAUTH2_CLIENT,
    /** Client certificate and PKCS#8 or PKCS#1 private key, both PEM. */
    MTLS,
    /** Process variable whose value is the bearer token. */
    BEARER_VARIABLE,
    /** HmacSHA256 of method, path, and body. */
    HMAC,
    AWS_SIG_V4
}
