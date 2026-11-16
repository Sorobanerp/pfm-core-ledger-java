package br.com.pfm.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Service that unfolds parcelled purchases ({@code installment_current} to {@code installment_total})
 * into future {@link InstallmentSchedule} entries ({@code SCHEDULED}), applying exact cent adjustment
 * on the last installment using {@link RoundingMode#HALF_EVEN} without any rounding loss.
 * Zero external framework imports.
 */
public final class InstallmentSplitter {

    /**
     * Splits a total purchase amount into {@code totalInstallments} using {@link RoundingMode#HALF_EVEN}
     * at 2-decimal cent precision (stored at scale 4) and adjusts any cent difference on the LAST installment
     * so that {@code sum(installments) == totalPurchaseAmount} with zero loss.
     * Generates {@link InstallmentSchedule} items from {@code installmentCurrent} through {@code totalInstallments}
     * with status {@link InstallmentSchedule.InstallmentStatus#SCHEDULED}.
     */
    public List<InstallmentSchedule> splitPurchase(
            UUID tenantId,
            UUID creditCardId,
            UUID parentLedgerEntryId,
            Money totalPurchaseAmount,
            int installmentCurrent,
            int totalInstallments,
            YearMonth currentCompetenceMonth,
            int dueDay
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(creditCardId, "creditCardId must not be null");
        Objects.requireNonNull(parentLedgerEntryId, "parentLedgerEntryId must not be null");
        Objects.requireNonNull(totalPurchaseAmount, "totalPurchaseAmount must not be null");
        Objects.requireNonNull(currentCompetenceMonth, "currentCompetenceMonth must not be null");

        if (!totalPurchaseAmount.isPositive()) {
            throw new IllegalArgumentException("totalPurchaseAmount must be positive");
        }
        if (totalInstallments < 1) {
            throw new IllegalArgumentException("totalInstallments must be >= 1");
        }
        if (installmentCurrent < 1 || installmentCurrent > totalInstallments) {
            throw new IllegalArgumentException("installmentCurrent must be between 1 and totalInstallments");
        }
        if (dueDay < 1 || dueDay > 31) {
            throw new IllegalArgumentException("dueDay must be between 1 and 31");
        }

        List<Money> allInstallmentAmounts = computeInstallmentAmountsLastAdjusted(
                totalPurchaseAmount,
                totalInstallments
        );

        List<InstallmentSchedule> schedules = new ArrayList<>(totalInstallments - installmentCurrent + 1);
        for (int number = installmentCurrent; number <= totalInstallments; number++) {
            int monthOffset = number - installmentCurrent;
            YearMonth targetMonth = currentCompetenceMonth.plusMonths(monthOffset);
            int clampedDueDay = Math.min(dueDay, targetMonth.lengthOfMonth());
            LocalDate dueDate = targetMonth.atDay(clampedDueDay);

            schedules.add(new InstallmentSchedule(
                    UUID.randomUUID(),
                    tenantId,
                    creditCardId,
                    parentLedgerEntryId,
                    number,
                    totalInstallments,
                    targetMonth.toString(),
                    dueDate,
                    allInstallmentAmounts.get(number - 1),
                    InstallmentSchedule.InstallmentStatus.SCHEDULED
            ));
        }

        return Collections.unmodifiableList(schedules);
    }

    /**
     * Computes exact installment amounts where installments {@code 1..N-1} use {@code HALF_EVEN} at 2 cent decimals
     * and the LAST installment ({@code N}) absorbs the exact cent difference {@code total - sum(1..N-1)}.
     */
    public List<Money> computeInstallmentAmountsLastAdjusted(Money totalPurchaseAmount, int totalInstallments) {
        Objects.requireNonNull(totalPurchaseAmount, "totalPurchaseAmount must not be null");
        if (totalInstallments < 1) {
            throw new IllegalArgumentException("totalInstallments must be >= 1");
        }
        if (totalInstallments == 1) {
            return List.of(totalPurchaseAmount);
        }

        BigDecimal divisor = BigDecimal.valueOf(totalInstallments);
        BigDecimal regularInstallment = totalPurchaseAmount.amount()
                .divide(divisor, Money.DISPLAY_SCALE, RoundingMode.HALF_EVEN)
                .setScale(Money.SCALE, RoundingMode.HALF_EVEN);

        BigDecimal sumPrior = regularInstallment.multiply(BigDecimal.valueOf(totalInstallments - 1L))
                .setScale(Money.SCALE, RoundingMode.HALF_EVEN);
        BigDecimal lastInstallment = totalPurchaseAmount.amount()
                .subtract(sumPrior)
                .setScale(Money.SCALE, RoundingMode.HALF_EVEN);

        List<Money> amounts = new ArrayList<>(totalInstallments);
        for (int i = 1; i < totalInstallments; i++) {
            amounts.add(Money.of(regularInstallment, totalPurchaseAmount.currency()));
        }
        amounts.add(Money.of(lastInstallment, totalPurchaseAmount.currency()));
        return Collections.unmodifiableList(amounts);
    }
}
