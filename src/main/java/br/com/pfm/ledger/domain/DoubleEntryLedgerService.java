package br.com.pfm.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure Domain Service enforcing Double-Entry Accounting, Accrual vs. Cash separation (RN01),
 * and Credit Limit != Budget future commitment validation (RN02).
 * Zero external framework imports.
 */
public final class DoubleEntryLedgerService {

    public static final String PATRIMONIO_CATEGORY = "PATRIMONIO";
    public static final String CARD_PAYMENT_SUBCATEGORY = "PAGAMENTO_FATURA_CARTAO";
    public static final String INTERNAL_TRANSFER_SUBCATEGORY = "TRANSFERENCIA_MESMA_TITULARIDADE";

    public record DoubleEntryPosting(
            LedgerEntry debitLeg,
            LedgerEntry creditLeg,
            CreditCard updatedCard,
            boolean impactsCategoryExpenseBudget
    ) {
        public DoubleEntryPosting {
            Objects.requireNonNull(debitLeg, "debitLeg must not be null");
            Objects.requireNonNull(creditLeg, "creditLeg must not be null");
            if (debitLeg.direction() != LedgerEntry.EntryDirection.DEBIT) {
                throw new IllegalArgumentException("First leg must be DEBIT");
            }
            if (creditLeg.direction() != LedgerEntry.EntryDirection.CREDIT) {
                throw new IllegalArgumentException("Second leg must be CREDIT");
            }
            if (!debitLeg.amount().equals(creditLeg.amount())) {
                throw new IllegalArgumentException("Double-entry imbalance: DEBIT != CREDIT");
            }
        }
    }

    public record OvercommitmentAlert(
            UUID tenantId,
            Money currentInvoice,
            Money fixedCommitments,
            Money futureInstallments,
            Money totalCommitted,
            Money regularNetIncome,
            Money excessAmount,
            BigDecimal commitmentPercentage,
            String alertCode
    ) {
    }

    public record FutureCommitmentEvaluation(
            Money totalCommitted,
            Money regularNetIncome,
            BigDecimal commitmentPercentage,
            boolean overcommitted,
            Optional<OvercommitmentAlert> alert
    ) {
    }

    /**
     * RN01 (Accrual / Competência):
     * Recording a credit card purchase debits the expense category on {@code competenceDate}
     * (with {@code cashDate == null}) and credits the credit card liability account.
     * RN02: Consumes the purchase amount from the card's available credit limit.
     */
    public DoubleEntryPosting recordCreditCardPurchase(
            UUID tenantId,
            CreditCard card,
            String categoryCode,
            String subcategoryCode,
            Money purchaseAmount,
            LocalDate competenceDate,
            String description,
            String idempotencyKey,
            boolean isInstallment
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(card, "card must not be null");
        Objects.requireNonNull(purchaseAmount, "purchaseAmount must not be null");
        Objects.requireNonNull(competenceDate, "competenceDate must not be null");

        Instant now = Instant.now();
        LedgerEntry.EntryType expenseType = isInstallment
                ? LedgerEntry.EntryType.INSTALLMENT_ACCRUAL
                : LedgerEntry.EntryType.STANDARD_EXPENSE;

        LedgerEntry expenseDebitLeg = new LedgerEntry(
                UUID.randomUUID(),
                tenantId,
                card.financialAccountId(),
                card.id(),
                categoryCode,
                subcategoryCode,
                LedgerEntry.EntryDirection.DEBIT,
                expenseType,
                purchaseAmount,
                competenceDate,
                null, // Cash outflow only occurs on invoice payment date (RN01)
                description,
                idempotencyKey,
                null,
                LedgerEntry.ReconciliationStatus.PENDING,
                now
        );

        LedgerEntry liabilityCreditLeg = new LedgerEntry(
                UUID.randomUUID(),
                tenantId,
                card.financialAccountId(),
                card.id(),
                PATRIMONIO_CATEGORY,
                CARD_PAYMENT_SUBCATEGORY,
                LedgerEntry.EntryDirection.CREDIT,
                expenseType,
                purchaseAmount,
                competenceDate,
                null,
                "Passivo Cartão - " + description,
                idempotencyKey + ":LIABILITY_CREDIT",
                expenseDebitLeg.id(),
                LedgerEntry.ReconciliationStatus.CONCILED,
                now
        );

        CreditCard updatedCard = card.consumeLimit(purchaseAmount);
        return new DoubleEntryPosting(expenseDebitLeg, liabilityCreditLeg, updatedCard, true);
    }

    /**
     * RN01 (Cash Settlement / Pagamento de Fatura - CARD_PAYMENT_TRANSFER):
     * Debits the credit card liability and credits the checking account on {@code paymentCashDate}
     * WITHOUT duplicating any category expense in the competence budget.
     */
    public DoubleEntryPosting recordCardPaymentTransfer(
            UUID tenantId,
            UUID checkingAccountId,
            CreditCard card,
            Money paymentAmount,
            LocalDate paymentCashDate,
            String idempotencyKey
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(checkingAccountId, "checkingAccountId must not be null");
        Objects.requireNonNull(card, "card must not be null");
        Objects.requireNonNull(paymentAmount, "paymentAmount must not be null");
        Objects.requireNonNull(paymentCashDate, "paymentCashDate must not be null");

        Instant now = Instant.now();

        LedgerEntry liabilityDebitLeg = new LedgerEntry(
                UUID.randomUUID(),
                tenantId,
                card.financialAccountId(),
                card.id(),
                PATRIMONIO_CATEGORY,
                CARD_PAYMENT_SUBCATEGORY,
                LedgerEntry.EntryDirection.DEBIT,
                LedgerEntry.EntryType.INVOICE_CASH_SETTLEMENT,
                paymentAmount,
                paymentCashDate,
                paymentCashDate,
                "CARD_PAYMENT_TRANSFER - Baixa Passivo Fatura " + card.cardName(),
                idempotencyKey + ":DEBIT_LIABILITY",
                null,
                LedgerEntry.ReconciliationStatus.CONCILED,
                now
        );

        LedgerEntry checkingCreditLeg = new LedgerEntry(
                UUID.randomUUID(),
                tenantId,
                checkingAccountId,
                card.id(),
                PATRIMONIO_CATEGORY,
                CARD_PAYMENT_SUBCATEGORY,
                LedgerEntry.EntryDirection.CREDIT,
                LedgerEntry.EntryType.TRANSFER_NEUTRAL,
                paymentAmount,
                paymentCashDate,
                paymentCashDate,
                "CARD_PAYMENT_TRANSFER - Saída Caixa Conta Corrente " + card.cardName(),
                idempotencyKey + ":CREDIT_CHECKING",
                liabilityDebitLeg.id(),
                LedgerEntry.ReconciliationStatus.CONCILED,
                now
        );

        // Restores available card limit upon invoice payment
        CreditCard restoredCard = new CreditCard(
                card.id(),
                card.tenantId(),
                card.financialAccountId(),
                card.cardName(),
                card.cardLastFour(),
                card.creditLimit(),
                card.availableLimit().add(paymentAmount),
                card.closingDay(),
                card.dueDay()
        );

        return new DoubleEntryPosting(liabilityDebitLeg, checkingCreditLeg, restoredCard, false);
    }

    /**
     * Computes net category competence expense from a list of ledger entries,
     * ignoring neutral transfers / invoice settlements (CARD_PAYMENT_TRANSFER)
     * and subtracting REFUND_CREDIT entries (RN01 & RN03).
     */
    public Money calculateNetCategoryExpense(List<LedgerEntry> entries, String categoryCode) {
        Objects.requireNonNull(entries, "entries must not be null");
        Objects.requireNonNull(categoryCode, "categoryCode must not be null");

        Money total = Money.zeroBrl();
        for (LedgerEntry entry : entries) {
            if (!categoryCode.equals(entry.categoryCode())) {
                continue;
            }
            if (entry.entryType() == LedgerEntry.EntryType.INVOICE_CASH_SETTLEMENT
                    || entry.entryType() == LedgerEntry.EntryType.TRANSFER_NEUTRAL) {
                continue;
            }
            if (entry.direction() == LedgerEntry.EntryDirection.DEBIT) {
                total = total.add(entry.amount());
            } else if (entry.direction() == LedgerEntry.EntryDirection.CREDIT
                    && entry.entryType() == LedgerEntry.EntryType.REFUND_CREDIT) {
                total = total.subtract(entry.amount());
            }
        }
        return total;
    }

    /**
     * RN02 ("Limite != Orçamento"):
     * Evaluates whether {@code currentInvoice + fixedExpenses + futureInstallments > regularNetIncome}
     * across M..M+N and emits an {@link OvercommitmentAlert} when exceeded, regardless of available card credit limit.
     */
    public FutureCommitmentEvaluation evaluateFutureCommitment(
            UUID tenantId,
            Money currentInvoice,
            Money fixedExpenses,
            Money futureInstallments,
            Money regularNetIncome
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(currentInvoice, "currentInvoice must not be null");
        Objects.requireNonNull(fixedExpenses, "fixedExpenses must not be null");
        Objects.requireNonNull(futureInstallments, "futureInstallments must not be null");
        Objects.requireNonNull(regularNetIncome, "regularNetIncome must not be null");

        Money totalCommitted = currentInvoice.add(fixedExpenses).add(futureInstallments);
        BigDecimal commitmentPct = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_EVEN);
        if (regularNetIncome.isPositive()) {
            commitmentPct = totalCommitted.amount()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(regularNetIncome.amount(), 4, RoundingMode.HALF_EVEN);
        }

        if (totalCommitted.compareTo(regularNetIncome) > 0) {
            Money excess = totalCommitted.subtract(regularNetIncome);
            OvercommitmentAlert alert = new OvercommitmentAlert(
                    tenantId,
                    currentInvoice,
                    fixedExpenses,
                    futureInstallments,
                    totalCommitted,
                    regularNetIncome,
                    excess,
                    commitmentPct,
                    "RN02_LIMIT_EXCEEDS_REGULAR_NET_INCOME"
            );
            return new FutureCommitmentEvaluation(
                    totalCommitted,
                    regularNetIncome,
                    commitmentPct,
                    true,
                    Optional.of(alert)
            );
        }

        return new FutureCommitmentEvaluation(
                totalCommitted,
                regularNetIncome,
                commitmentPct,
                false,
                Optional.empty()
        );
    }
}
