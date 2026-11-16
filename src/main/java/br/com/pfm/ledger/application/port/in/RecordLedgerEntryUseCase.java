package br.com.pfm.ledger.application.port.in;

import br.com.pfm.ledger.domain.LedgerEntry;
import java.util.List;
import java.util.UUID;

/**
 * Inbound Port (Use Case) for recording and querying tenant-isolated Ledger Entries.
 */
public interface RecordLedgerEntryUseCase {

    LedgerEntry recordEntry(LedgerEntry entry);

    List<LedgerEntry> findAllForCurrentTenant();

    List<LedgerEntry> findByCompetenceMonth(UUID tenantId, String competenceYearMonth);
}
