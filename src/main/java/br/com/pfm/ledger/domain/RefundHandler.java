package br.com.pfm.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Service implementing RN03 (Refund & Chargeback Processing).
 * Processes refunds ({@code REFUND} / negative values) by crediting and reducing the spent balance
 * of the original purchase category, NEVER recording a refund as {@code STANDARD_INCOME} / {@code RECEITAS}.
 * Zero external framework imports.
 */
public final class RefundHandler {

    public record RefundOutcome(
            LedgerEntry refundCreditEntry,
            Money updatedCategoryNetSpend,
            boolean countedAsIncome
    ) {
    }

    /**
     * Processes a refund against an original purchase (or direct category expense),
     * accepting either a negative raw amount (e.g., {@code -168.9400}) or a positive refund magnitude,
     * and emitting a {@link LedgerEntry.EntryDirection#CREDIT} of type {@link LedgerEntry.EntryType#REFUND_CREDIT}
     * on the exact same {@code categoryCode} and {@code subcategoryCode} as the original purchase.
     */
    public RefundOutcome processRefund(
            LedgerEntry originalPurchase,
            BigDecimal rawRefundAmount,
            LocalDate refundCompetenceDate,
            String refundDescription,
            String idempotencyKey,
            Money currentCategorySpendBeforeRefund
    ) {
        Objects.requireNonNull(originalPurchase, "originalPurchase must not be null");
        Objects.requireNonNull(rawRefundAmount, "rawRefundAmount must not be null");
        Objects.requireNonNull(refundCompetenceDate, "refundCompetenceDate must not be null");
        Objects.requireNonNull(refundDescription, "refundDescription must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(currentCategorySpendBeforeRefund, "currentCategorySpendBeforeRefund must not be null");

        if (rawRefundAmount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Refund amount cannot be zero");
        }
        if ("RECEITAS".equalsIgnoreCase(originalPurchase.categoryCode())) {
            throw new IllegalArgumentException("RN03 violation: Refund cannot target RECEITAS (INCOME) category");
        }

        Money positiveRefundMagnitude = Money.of(
                rawRefundAmount.abs(),
                originalPurchase.amount().currency()
        );

        LedgerEntry refundEntry = new LedgerEntry(
                UUID.randomUUID(),
                originalPurchase.tenantId(),
                originalPurchase.accountId(),
                originalPurchase.creditCardId(),
                originalPurchase.categoryCode(),
                originalPurchase.subcategoryCode(),
                LedgerEntry.EntryDirection.CREDIT,
                LedgerEntry.EntryType.REFUND_CREDIT,
                positiveRefundMagnitude,
                refundCompetenceDate,
                null,
                refundDescription,
                idempotencyKey,
                originalPurchase.id(),
                LedgerEntry.ReconciliationStatus.CONCILED,
                Instant.now()
        );

        Money updatedCategoryNetSpend = currentCategorySpendBeforeRefund.subtract(positiveRefundMagnitude);
        return new RefundOutcome(refundEntry, updatedCategoryNetSpend, false);
    }
}
