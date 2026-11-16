package br.com.pfm.ledger.application.service;

import br.com.pfm.ledger.application.port.in.RecordLedgerEntryUseCase;
import br.com.pfm.ledger.application.port.out.LedgerEntryRepositoryPort;
import br.com.pfm.ledger.application.port.out.TransactionalOutboxPort;
import br.com.pfm.ledger.domain.LedgerEntry;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application Service orchestrating LedgerEntry persistence and Transactional Outbox emission (ADR-005).
 */
@Service
public class LedgerCommandService implements RecordLedgerEntryUseCase {

    private final LedgerEntryRepositoryPort ledgerEntryRepository;
    private final TransactionalOutboxPort outboxPort;

    public LedgerCommandService(
            LedgerEntryRepositoryPort ledgerEntryRepository,
            TransactionalOutboxPort outboxPort
    ) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.outboxPort = outboxPort;
    }

    @Override
    @Transactional
    public LedgerEntry recordEntry(LedgerEntry entry) {
        Objects.requireNonNull(entry, "LedgerEntry must not be null");
        LedgerEntry saved = ledgerEntryRepository.save(entry);
        String payload = """
                {"journalEntryId":"%s","tenantId":"%s","amount":"%s","currency":"%s","competenceDate":"%s"}"""
                .formatted(
                        saved.id(),
                        saved.tenantId(),
                        saved.amount().toCanonicalString(),
                        saved.amount().currency(),
                        saved.competenceDate()
                );
        outboxPort.appendOutboxEvent(
                saved.tenantId(),
                "LEDGER_ENTRY",
                saved.id(),
                "ledger.entry.committed",
                saved.idempotencyKey(),
                payload
        );
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntry> findAllForCurrentTenant() {
        return ledgerEntryRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntry> findByCompetenceMonth(UUID tenantId, String competenceYearMonth) {
        YearMonth ym = YearMonth.parse(competenceYearMonth);
        return ledgerEntryRepository.findByCompetenceDateBetween(ym.atDay(1), ym.atEndOfMonth());
    }
}
