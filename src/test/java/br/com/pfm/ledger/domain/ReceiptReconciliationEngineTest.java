package br.com.pfm.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure Domain Unit Tests for {@link ReceiptReconciliationEngine} (RN04 / UC-03 — ±3 days reconciliation & >10% EAN inflation).
 */
class ReceiptReconciliationEngineTest {

    private final ReceiptReconciliationEngine engine = new ReceiptReconciliationEngine();

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-aaaa-4aaa-8aaa-111111111111");
    private static final UUID CARD_ID = UUID.fromString("33333333-aaaa-4aaa-8aaa-333333333333");

    @Test
    @DisplayName("RN04 / UC-03 BDD: Concilia cupom NFC-e com lançamento de fatura (±3 dias, mesmo card_id e valor exato) sem duplicar gasto e detecta inflação >10% por EAN")
    void shouldReconcileWithinThreeDaysWithoutDuplicatingAndDetectEanInflationOverTenPercent() {
        // Statement entry posted 2 days after NFC-e emission (within ±3 days window), exact amount R$ 125,80
        UUID statementEntryId = UUID.randomUUID();
        LedgerEntry statementEntry = new LedgerEntry(
                statementEntryId,
                TENANT_ID,
                ACCOUNT_ID,
                CARD_ID,
                "ALIMENTACAO",
                "SUPERMERCADO_MERCEARIA",
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("125.8000"),
                LocalDate.of(2026, 3, 16),
                null,
                "ATACADAO SA",
                "sha256-stmt-atacadao-001",
                null,
                LedgerEntry.ReconciliationStatus.PENDING,
                Instant.now()
        );

        // Item 1: Café Torrado 500g (EAN 7891000100103) jumped from R$ 20,00 to R$ 23,50 (+17.50% -> >10% ALERT)
        // Item 2: Arroz Tipo 1 5kg (EAN 7891000200202) moved from R$ 30,00 to R$ 31,50 (+5.00% -> <=10% NO ALERT)
        List<ReceiptReconciliationEngine.ReceiptItem> items = List.of(
                new ReceiptReconciliationEngine.ReceiptItem(
                        UUID.randomUUID(),
                        TENANT_ID,
                        null,
                        "24260312345678000199650010000123451000123456",
                        1,
                        "7891000100103",
                        "CAFE TORRADO E MOIDO 500G",
                        new BigDecimal("2.0000"),
                        "UN",
                        Money.brl("23.5000"),
                        Money.brl("47.0000"),
                        Instant.parse("2026-03-14T14:20:00Z")
                ),
                new ReceiptReconciliationEngine.ReceiptItem(
                        UUID.randomUUID(),
                        TENANT_ID,
                        null,
                        "24260312345678000199650010000123451000123456",
                        2,
                        "7891000200202",
                        "ARROZ BRANCO TIPO 1 5KG",
                        new BigDecimal("2.5016"),
                        "UN",
                        Money.brl("31.5000"),
                        Money.brl("78.8000"),
                        Instant.parse("2026-03-14T14:20:00Z")
                )
        );

        Map<String, ReceiptReconciliationEngine.HistoricalEanPrice> history = Map.of(
                "7891000100103", new ReceiptReconciliationEngine.HistoricalEanPrice(
                        "7891000100103",
                        "CAFE TORRADO E MOIDO 500G",
                        Money.brl("20.0000"),
                        LocalDate.of(2026, 2, 10),
                        "ATACADAO SA"
                ),
                "7891000200202", new ReceiptReconciliationEngine.HistoricalEanPrice(
                        "7891000200202",
                        "ARROZ BRANCO TIPO 1 5KG",
                        Money.brl("30.0000"),
                        LocalDate.of(2026, 2, 10),
                        "ATACADAO SA"
                )
        );

        ReceiptReconciliationEngine.FiscalReceiptCandidate receipt =
                new ReceiptReconciliationEngine.FiscalReceiptCandidate(
                        UUID.randomUUID(),
                        TENANT_ID,
                        CARD_ID,
                        "24260312345678000199650010000123451000123456",
                        "ATACADAO SA",
                        LocalDate.of(2026, 3, 14),
                        Money.brl("125.8000"),
                        ReceiptReconciliationEngine.ReceiptLifecycleStatus.PENDING_RECONCILIATION,
                        items
                );

        ReceiptReconciliationEngine.ReconciliationResult result = engine.reconcileAndDetectInflation(
                receipt,
                List.of(statementEntry),
                history
        );

        // 1. Unified to RECONCILED without duplicating ledger expense
        assertThat(result.status()).isEqualTo(ReceiptReconciliationEngine.ReceiptLifecycleStatus.RECONCILED);
        assertThat(result.createdDuplicateLedgerExpense()).isFalse();
        assertThat(result.reconciledLedgerEntry()).isPresent();
        assertThat(result.reconciledLedgerEntry().orElseThrow().id()).isEqualTo(statementEntryId);
        assertThat(result.reconciledLedgerEntry().orElseThrow().reconciliationStatus())
                .isEqualTo(LedgerEntry.ReconciliationStatus.CONCILED);

        // 2. Receipt items linked to the existing statement LedgerEntry
        assertThat(result.linkedItems())
                .hasSize(2)
                .allMatch(item -> statementEntryId.equals(item.ledgerEntryId()));

        // 3. Personal inflation alert generated ONLY for EAN 7891000100103 (+17.5000% > 10%)
        assertThat(result.inflationAlerts()).hasSize(1);
        ReceiptReconciliationEngine.PriceInflationAlert alert = result.inflationAlerts().getFirst();
        assertThat(alert.ean()).isEqualTo("7891000100103");
        assertThat(alert.previousUnitPrice()).isEqualTo(Money.brl("20.0000"));
        assertThat(alert.currentUnitPrice()).isEqualTo(Money.brl("23.5000"));
        assertThat(alert.inflationPercentage()).isEqualByComparingTo(new BigDecimal("17.5000"));
    }

    @Test
    @DisplayName("Não concilia quando a diferença de datas excede a janela de ±3 dias")
    void shouldKeepPendingReconciliationWhenDateDifferenceExceedsThreeDays() {
        LedgerEntry statementEntry = new LedgerEntry(
                UUID.randomUUID(),
                TENANT_ID,
                ACCOUNT_ID,
                CARD_ID,
                "ALIMENTACAO",
                "SUPERMERCADO_MERCEARIA",
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.STANDARD_EXPENSE,
                Money.brl("125.8000"),
                LocalDate.of(2026, 3, 20), // 6 days after receipt issue date (2026-03-14)
                null,
                "ATACADAO SA",
                "sha256-stmt-atacadao-002",
                null,
                LedgerEntry.ReconciliationStatus.PENDING,
                Instant.now()
        );

        ReceiptReconciliationEngine.FiscalReceiptCandidate receipt =
                new ReceiptReconciliationEngine.FiscalReceiptCandidate(
                        UUID.randomUUID(),
                        TENANT_ID,
                        CARD_ID,
                        "24260312345678000199650010000123451000999999",
                        "ATACADAO SA",
                        LocalDate.of(2026, 3, 14),
                        Money.brl("125.8000"),
                        ReceiptReconciliationEngine.ReceiptLifecycleStatus.PENDING_RECONCILIATION,
                        List.of()
                );

        ReceiptReconciliationEngine.ReconciliationResult result = engine.reconcileAndDetectInflation(
                receipt,
                List.of(statementEntry),
                Map.of()
        );

        assertThat(result.status()).isEqualTo(ReceiptReconciliationEngine.ReceiptLifecycleStatus.PENDING_RECONCILIATION);
        assertThat(result.reconciledLedgerEntry()).isEmpty();
    }
}
