package io.github.illuseahashmap.workflow.shared.context;

/**
 * Role codes. User roles come from the {@code eim_roles} claim of the Keycloak access token
 * (assigned in EIM); {@code SERVICE} is the system-to-system principal. Endpoints list the
 * roles they accept in {@code @PreAuthorize("hasAnyRole(...)")}, as rsa-core-service does.
 */
public final class WorkflowRoles {

    /** Platform administrator: tenants, members, all tenants' data (bypasses row-level security). */
    public static final String PLATFORM_ADMIN = "WF_PLATFORM_ADMIN";
    /** Designs and runs processes: definitions, assignment rules, services, agents. */
    public static final String ADMIN = "WF_ADMIN";
    /** Starts processes and handles tasks. */
    public static final String OPERATOR = "WF_OPERATOR";
    /** Read-only. */
    public static final String VIEWER = "WF_VIEWER";
    /** System-to-system caller ({@code X-Workflow-Token}). */
    public static final String SERVICE = "SERVICE";

    private WorkflowRoles() {
    }
}
