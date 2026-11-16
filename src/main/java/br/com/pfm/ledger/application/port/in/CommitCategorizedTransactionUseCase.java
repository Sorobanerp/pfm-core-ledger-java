package br.com.pfm.ledger.application.port.in;

import br.com.pfm.ledger.domain.InstallmentSchedule;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Inbound Use Case Port for consuming categorized transactions (`tx.categorized`),
 * verifying idempotency, applying installment splitting / refund / reconciliation,
 * persisting to PostgreSQL under RLS, and appending `LedgerEntryCommittedEvent` to `outbox_events`
 * in the same ACID transaction.
 */
public interface CommitCategorizedTransactionUseCase {

    record CategorizedTransactionCommand(
            UUID tenantId,
            UUID accountId,
            UUID creditCardId,
            String idempotencyKey,
            String categoryCode,
            String subcategoryCode,
            BigDecimal amount,
            String currency,
            LocalDate competenceDate,
            String description,
            int installmentCurrent,
            int installmentTotal,
            int cardDueDay,
            UUID originalPurchaseEntryId
    ) {
    }

    record CommitTransactionResult(
            LedgerEntry primaryEntry,
            List<InstallmentSchedule> generatedSchedules,
            boolean idempotentReplay,
            Money resultingCategorySpend
    ) {
    }

    CommitTransactionResult commitCategorizedTransaction(CategorizedTransactionCommand command);
}
