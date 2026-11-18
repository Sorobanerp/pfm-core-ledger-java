package br.com.pfm.ledger.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TenantContextHolder ScopedValue Unit Tests")
class TenantContextHolderTest {

    @Test
    void shouldReturnEmptyWhenNotBound() {
        Optional<UUID> tenantId = TenantContextHolder.getTenantId();
        assertTrue(tenantId.isEmpty());
        assertThrows(IllegalStateException.class, TenantContextHolder::requireTenantId);
    }

    @Test
    void shouldPropagateTenantWithRunWithTenant() {
        UUID expectedTenantId = UUID.randomUUID();
        AtomicReference<UUID> captured = new AtomicReference<>();

        TenantContextHolder.runWithTenant(expectedTenantId, () -> {
            captured.set(TenantContextHolder.requireTenantId());
            assertEquals(Optional.of(expectedTenantId), TenantContextHolder.getTenantId());
        });

        assertEquals(expectedTenantId, captured.get());
        assertTrue(TenantContextHolder.getTenantId().isEmpty());
    }

    @Test
    void shouldPropagateTenantWithCallWithTenant() throws Exception {
        UUID expectedTenantId = UUID.randomUUID();

        String result = TenantContextHolder.callWithTenant(expectedTenantId, () -> {
            assertEquals(expectedTenantId, TenantContextHolder.requireTenantId());
            return "success-" + expectedTenantId;
        });

        assertEquals("success-" + expectedTenantId, result);
        assertTrue(TenantContextHolder.getTenantId().isEmpty());
    }

    @Test
    void shouldValidateNullArguments() {
        assertThrows(NullPointerException.class, () -> TenantContextHolder.runWithTenant(null, () -> {}));
        assertThrows(NullPointerException.class, () -> TenantContextHolder.runWithTenant(UUID.randomUUID(), null));
        assertThrows(NullPointerException.class, () -> TenantContextHolder.callWithTenant(null, () -> "val"));
        assertThrows(NullPointerException.class, () -> TenantContextHolder.callWithTenant(UUID.randomUUID(), null));
    }
}
