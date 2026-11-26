package br.com.pfm.ledger.application.port.out;

import br.com.pfm.ledger.domain.AuditLogEntry;
import java.util.List;
import java.util.UUID;

/**
 * Outbound Port for persisting and querying immutable append-only audit log entries (ADR-010).
 */
public interface AuditLogRepositoryPort {

    void append(AuditLogEntry entry);

    List<AuditLogEntry> findByTenantId(UUID tenantId);
}
