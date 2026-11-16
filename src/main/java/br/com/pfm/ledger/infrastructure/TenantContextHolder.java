package br.com.pfm.ledger.infrastructure;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Zero-Trust Multitenancy Context Holder using Java 25 LTS {@link ScopedValue} (ADR-006 / RN05).
 * Safely propagates the immutable tenant UUID across Virtual Threads without ThreadLocal memory leaks.
 */
public final class TenantContextHolder {

    public static final ScopedValue<UUID> CURRENT_TENANT = ScopedValue.newInstance();

    private TenantContextHolder() {
    }

    public static Optional<UUID> getTenantId() {
        if (CURRENT_TENANT.isBound()) {
            return Optional.ofNullable(CURRENT_TENANT.get());
        }
        return Optional.empty();
    }

    public static UUID requireTenantId() {
        return getTenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant_id bound to current Java 25 ScopedValue context"));
    }

    public static void runWithTenant(UUID tenantId, Runnable action) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        ScopedValue.where(CURRENT_TENANT, tenantId).run(action);
    }

    public static <T> T callWithTenant(UUID tenantId, Callable<T> callable) throws Exception {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(callable, "callable must not be null");
        return ScopedValue.where(CURRENT_TENANT, tenantId).call(callable::call);
    }
}
