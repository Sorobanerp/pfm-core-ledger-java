package br.com.pfm.ledger.application.port.out;

import br.com.pfm.ledger.domain.CreditCard;
import br.com.pfm.ledger.domain.InstallmentSchedule;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.domain.ReceiptReconciliationEngine;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound Port for persisting and querying LedgerEntry, InstallmentSchedule, CreditCard,
 * and ReceiptItem domain aggregates under PostgreSQL RLS.
 */
public interface LedgerEntryRepositoryPort {

    LedgerEntry save(LedgerEntry entry);

    Optional<LedgerEntry> findById(UUID id);

    default Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey) {
        return Optional.empty();
    }

    default LedgerEntry updateReconciliationStatus(UUID entryId, LedgerEntry.ReconciliationStatus status) {
        throw new UnsupportedOperationException("updateReconciliationStatus not implemented");
    }

    default void saveInstallmentSchedules(List<InstallmentSchedule> schedules) {
    }

    default List<InstallmentSchedule> findInstallmentSchedulesByMonth(String competenceMonth) {
        return List.of();
    }

    default List<InstallmentSchedule> findAllInstallmentSchedules() {
        return List.of();
    }

    default void saveReceiptItems(List<ReceiptReconciliationEngine.ReceiptItem> items) {
    }

    default Map<String, ReceiptReconciliationEngine.HistoricalEanPrice> findLatestPricesByEan(List<String> eans) {
        return Map.of();
    }

    default List<CreditCard> findAllCreditCards() {
        return List.of();
    }

    default Optional<CreditCard> findCreditCardById(UUID cardId) {
        return Optional.empty();
    }

    default Money findTenantMonthlyNetIncome(UUID tenantId) {
        return Money.zeroBrl();
    }

    List<LedgerEntry> findAll();

    List<LedgerEntry> findByCompetenceDateBetween(LocalDate startInclusive, LocalDate endInclusive);
}
