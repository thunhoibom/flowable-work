package io.github.illuseahashmap.workflow.auth.application.port;

/** How users authenticated by the external identity provider are mapped to local accounts. */
public interface ExternalProvisioningPolicy {

    /** Create a local account on first sign-in when none exists. */
    boolean autoProvision();

    /** Home tenant and first membership of an auto-provisioned account. */
    String defaultTenantCode();
}
