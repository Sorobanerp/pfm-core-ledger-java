package br.com.pfm.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure Domain Unit Tests for {@link DoubleEntryLedgerService} (RN01 Accrual vs. Cash & RN02 Limit != Budget).
 */
class DoubleEntryLedgerServiceTest {

    private final DoubleEntryLedgerService service = new DoubleEntryLedgerService();

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID CHECKING_ACCOUNT_ID = UUID.fromString("11111111-aaaa-4aaa-8aaa-111111111111");
    private static final UUID CARD_ACCOUNT_ID = UUID.fromString("22222222-aaaa-4aaa-8aaa-222222222222");
    private static final UUID CARD_ID = UUID.fromString("33333333-aaaa-4aaa-8aaa-333333333333");

    @Test
    @DisplayName("RN01 BDD: Compra no cartão debita despesa na competência e CARD_PAYMENT_TRANSFER baixa passivo sem duplicar gasto de categoria")
    void shouldSeparateAccrualFromCashAndNotDuplicateCategoryExpenseOnCardPaymentTransfer() {
        CreditCard card = new CreditCard(
                CARD_ID,
                TENANT_ID,
                CARD_ACCOUNT_ID,
                "Itaú Personnalité Visa Infinite",
                "9874",
                Money.brl("30000.0000"),
                Money.brl("30000.0000"),
                25,
                5
        );

        // 1. Purchase on 2026-03-14 (Competence): R$ 450,00 in ALIMENTACAO / RESTAURANTES_GASTRONOMIA
        DoubleEntryLedgerService.DoubleEntryPosting purchasePosting = service.recordCreditCardPurchase(
                TENANT_ID,
                card,
                "ALIMENTACAO",
                "RESTAURANTES_GASTRONOMIA",
                Money.brl("450.0000"),
                LocalDate.of(2026, 3, 14),
                "CHURRASCARIA GAUCHA",
                "sha256-purchase-001",
                false
        );

        assertThat(purchasePosting.impactsCategoryExpenseBudget()).isTrue();
        assertThat(purchasePosting.debitLeg().direction()).isEqualTo(LedgerEntry.EntryDirection.DEBIT);
        assertThat(purchasePosting.debitLeg().categoryCode()).isEqualTo("ALIMENTACAO");
        assertThat(purchasePosting.debitLeg().competenceDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(purchasePosting.debitLeg().cashDate()).isNull();
        assertThat(purchasePosting.creditLeg().direction()).isEqualTo(LedgerEntry.EntryDirection.CREDIT);
        assertThat(purchasePosting.creditLeg().categoryCode()).isEqualTo("PATRIMONIO");
        assertThat(purchasePosting.updatedCard().availableLimit()).isEqualTo(Money.brl("29550.0000"));

        // 2. Invoice payment on 2026-04-05 (Cash): CARD_PAYMENT_TRANSFER of R$ 450,00
        DoubleEntryLedgerService.DoubleEntryPosting paymentTransfer = service.recordCardPaymentTransfer(
                TENANT_ID,
                CHECKING_ACCOUNT_ID,
                purchasePosting.updatedCard(),
                Money.brl("450.0000"),
                LocalDate.of(2026, 4, 5),
                "sha256-card-payment-001"
        );

        assertThat(paymentTransfer.impactsCategoryExpenseBudget()).isFalse();
        assertThat(paymentTransfer.debitLeg().entryType()).isEqualTo(LedgerEntry.EntryType.INVOICE_CASH_SETTLEMENT);
        assertThat(paymentTransfer.creditLeg().entryType()).isEqualTo(LedgerEntry.EntryType.TRANSFER_NEUTRAL);
        assertThat(paymentTransfer.updatedCard().availableLimit()).isEqualTo(Money.brl("30000.0000"));

        // 3. Total category expense in ALIMENTACAO must remain strictly R$ 450,0000 (not duplicated to R$ 900,0000)
        List<LedgerEntry> allEntries = List.of(
                purchasePosting.debitLeg(),
                purchasePosting.creditLeg(),
                paymentTransfer.debitLeg(),
                paymentTransfer.creditLeg()
        );
        Money netAlimentacaoSpend = service.calculateNetCategoryExpense(allEntries, "ALIMENTACAO");
        assertThat(netAlimentacaoSpend).isEqualTo(Money.brl("450.0000"));
    }

    @Test
    @DisplayName("RN02 BDD: Limite != Orçamento — Emite alerta de domínio se fatura_atual + fixos + parcelas_futuras > renda_liquida_regular")
    void shouldEmitOvercommitmentAlertWhenCommitmentsExceedRegularNetIncomeEvenWithHighCardLimit() {
        Money currentInvoice = Money.brl("6200.0000");
        Money fixedExpenses = Money.brl("4500.0000");
        Money futureInstallments = Money.brl("3800.0000");
        Money regularNetIncome = Money.brl("12000.0000");

        DoubleEntryLedgerService.FutureCommitmentEvaluation evaluation = service.evaluateFutureCommitment(
                TENANT_ID,
                currentInvoice,
                fixedExpenses,
                futureInstallments,
                regularNetIncome
        );

        assertThat(evaluation.overcommitted()).isTrue();
        assertThat(evaluation.totalCommitted()).isEqualTo(Money.brl("14500.0000"));
        assertThat(evaluation.commitmentPercentage()).isEqualByComparingTo(new BigDecimal("120.8333"));
        assertThat(evaluation.alert()).isPresent();

        DoubleEntryLedgerService.OvercommitmentAlert alert = evaluation.alert().orElseThrow();
        assertThat(alert.excessAmount()).isEqualTo(Money.brl("2500.0000"));
        assertThat(alert.alertCode()).isEqualTo("RN02_LIMIT_EXCEEDS_REGULAR_NET_INCOME");
    }
}
