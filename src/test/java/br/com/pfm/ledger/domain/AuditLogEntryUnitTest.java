package br.com.pfm.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogEntryUnitTest {

    @Test
    void shouldCreateAuditLogEntryWithDefaults() {
        UUID tenantId = UUID.randomUUID();
        AuditLogEntry entry = AuditLogEntry.of(
                tenantId,
                "PLATFORM_ADMIN",
                "PLATFORM_ADMIN",
                "TENANT_PLAN_CHANGED",
                "SUBSCRIPTION",
                tenantId.toString(),
                "4bf92f3577b34da6a3ce929d0e0e4736",
                null
        );

        assertNotNull(entry.id());
        assertEquals(tenantId, entry.tenantId());
        assertEquals("{}", entry.metadataJson());
        assertNotNull(entry.createdAt());
    }

    @Test
    void shouldRejectBlankActorActionOrTraceId() {
        UUID tenantId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                AuditLogEntry.of(tenantId, " ", "OWNER", "ACTION", "RES", "1", "trace123", "{}")
        );
        assertThrows(IllegalArgumentException.class, () ->
                AuditLogEntry.of(tenantId, "actor", "OWNER", " ", "RES", "1", "trace123", "{}")
        );
        assertThrows(IllegalArgumentException.class, () ->
                AuditLogEntry.of(tenantId, "actor", "OWNER", "ACTION", "RES", "1", " ", "{}")
        );
    }
}
