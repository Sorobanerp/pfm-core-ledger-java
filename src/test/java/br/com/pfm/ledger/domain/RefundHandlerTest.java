package br.com.pfm.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure Domain Unit Tests for {@link RefundHandler} (RN03 — Refund -R$ 168,94 deducts original category, never INCOME).
 */
class RefundHandlerTest {

    private final RefundHandler refundHandler = new RefundHandler();
    private final DoubleEntryLedgerService ledgerService = new DoubleEntryLedgerService();

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-aaaa-4aaa-8aaa-111111111111");
    private static final UUID CARD_ID = UUID.fromString("33333333-aaaa-4aaa-8aaa-333333333333");

    @Test
    @DisplayName("RN03 BDD: Estorno de -R$ 168,94 abate saldo gasto da categoria original da compra e NUNCA contabiliza como INCOME")
    void shouldDeductRefundOf16894FromOriginalCategorySpendAndNeverCountAsIncome() {
        LedgerEntry originalPurchase = new LedgerEntry(
                UUID.randomUUID(),
                TENANT_ID,
                ACCOUNT_ID,
                CARD_ID,
                "LAZER",
                "VESTUARIO_CALCADOS",
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("400.0000"),
                LocalDate.of(2026, 3, 10),
                null,
                "LOJAS RENNER FL 99",
                "sha256-orig-purchase-001",
                null,
                LedgerEntry.ReconciliationStatus.CONCILED,
                Instant.now()
        );

        RefundHandler.RefundOutcome outcome = refundHandler.processRefund(
                originalPurchase,
                new BigDecimal("-168.9400"),
                LocalDate.of(2026, 3, 18),
                "ESTORNO LOJAS RENNER -R$ 168,94",
                "sha256-refund-16894",
                Money.brl("400.0000")
        );

        LedgerEntry refundEntry = outcome.refundCreditEntry();
        assertThat(outcome.countedAsIncome()).isFalse();
        assertThat(refundEntry.direction()).isEqualTo(LedgerEntry.EntryDirection.CREDIT);
        assertThat(refundEntry.entryType()).isEqualTo(LedgerEntry.EntryType.REFUND_CREDIT);
        assertThat(refundEntry.entryType()).isNotEqualTo(LedgerEntry.EntryType.STANDARD_INCOME);
        assertThat(refundEntry.categoryCode()).isEqualTo("LAZER");
        assertThat(refundEntry.categoryCode()).isNotEqualTo("RECEITAS");
        assertThat(refundEntry.subcategoryCode()).isEqualTo("VESTUARIO_CALCADOS");
        assertThat(refundEntry.amount()).isEqualTo(Money.brl("168.9400"));
        assertThat(refundEntry.originalTransactionId()).isEqualTo(originalPurchase.id());

        // Category spend reduced from 400.0000 by 168.9400 -> 231.0600
        assertThat(outcome.updatedCategoryNetSpend()).isEqualTo(Money.brl("231.0600"));

        Money computedFromLedger = ledgerService.calculateNetCategoryExpense(
                List.of(originalPurchase, refundEntry),
                "LAZER"
        );
        assertThat(computedFromLedger).isEqualTo(Money.brl("231.0600"));
    }
}
