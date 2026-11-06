package io.github.illuseahashmap.workflow.shared.context;

import java.util.function.Supplier;

public final class TenantContext {

    private static final ThreadLocal<TenantInfo> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantInfo tenantInfo) {
        TENANT.set(tenantInfo);
    }

    public static TenantInfo current() {
        TenantInfo tenantInfo = TENANT.get();
        if (tenantInfo == null) {
            throw new IllegalStateException("Tenant context is missing");
        }
        return tenantInfo;
    }

    public static void clear() {
        TENANT.remove();
    }

    /**
     * Runs {@code action} with {@code tenantInfo} as the current tenant, then restores whatever was
     * there before (possibly nothing). For engine callbacks that may run outside a request.
     */
    public static <T> T callWith(TenantInfo tenantInfo, Supplier<T> action) {
        TenantInfo previous = TENANT.get();
        TENANT.set(tenantInfo);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                TENANT.remove();
            } else {
                TENANT.set(previous);
            }
        }
    }

    public record TenantInfo(String tenantId, String tenantCode, String tenantName) {
    }
}
