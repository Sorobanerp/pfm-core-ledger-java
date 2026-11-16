package br.com.pfm.ledger.application.port.out;

import br.com.pfm.ledger.domain.LedgerEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound Port for persisting and querying LedgerEntry domain aggregates under PostgreSQL RLS.
 */
public interface LedgerEntryRepositoryPort {

    LedgerEntry save(LedgerEntry entry);

    Optional<LedgerEntry> findById(UUID id);

    List<LedgerEntry> findAll();

    List<LedgerEntry> findByCompetenceDateBetween(LocalDate startInclusive, LocalDate endInclusive);
}
