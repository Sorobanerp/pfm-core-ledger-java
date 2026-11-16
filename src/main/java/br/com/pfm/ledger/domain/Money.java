package br.com.pfm.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure Domain Value Object representing an exact monetary amount (RN06).
 * Enforces scale 4 (NUMERIC(19,4)) and banker's rounding (HALF_EVEN) for internal storage
 * and scale 2 for installment cent-residue allocation without any floating-point IEEE 754 types.
 */
public final class Money implements Comparable<Money> {

    public static final int SCALE = 4;
    public static final int DISPLAY_SCALE = 2;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;
    public static final String DEFAULT_CURRENCY = "BRL";

    private final BigDecimal amount;
    private final String currency;

    private Money(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "Monetary amount must not be null");
        Objects.requireNonNull(currency, "Currency code must not be null");
        if (currency.isBlank() || currency.trim().length() != 3) {
            throw new IllegalArgumentException("Currency must be a valid 3-letter ISO-4217 code");
        }
        this.amount = amount.setScale(SCALE, ROUNDING_MODE);
        this.currency = currency.trim().toUpperCase();
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(String exactDecimalAmount, String currency) {
        Objects.requireNonNull(exactDecimalAmount, "Decimal string must not be null");
        return new Money(new BigDecimal(exactDecimalAmount.trim()), currency);
    }

    public static Money brl(String exactDecimalAmount) {
        return of(exactDecimalAmount, DEFAULT_CURRENCY);
    }

    public static Money brl(BigDecimal amount) {
        return of(amount, DEFAULT_CURRENCY);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money zeroBrl() {
        return zero(DEFAULT_CURRENCY);
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money multiply(int factor) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)), this.currency);
    }

    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "Factor must not be null");
        return new Money(this.amount.multiply(factor), this.currency);
    }

    /**
     * Splits a total purchase amount into N installments (RN06), allocating any cent residue
     * strictly to the 1st installment so that sum(installments) == total.
     */
    public List<Money> splitIntoInstallments(int totalInstallments) {
        if (totalInstallments < 1) {
            throw new IllegalArgumentException("Total installments must be >= 1");
        }
        if (totalInstallments == 1) {
            return List.of(this);
        }
        BigDecimal divisor = BigDecimal.valueOf(totalInstallments);
        BigDecimal baseCents = this.amount
                .divide(divisor, DISPLAY_SCALE, RoundingMode.FLOOR)
                .setScale(SCALE, ROUNDING_MODE);
        BigDecimal residue = this.amount.subtract(baseCents.multiply(divisor)).setScale(SCALE, ROUNDING_MODE);

        List<Money> schedule = new ArrayList<>(totalInstallments);
        schedule.add(new Money(baseCents.add(residue), this.currency));
        for (int i = 2; i <= totalInstallments; i++) {
            schedule.add(new Money(baseCents, this.currency));
        }
        return Collections.unmodifiableList(schedule);
    }

    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public String toCanonicalString() {
        return amount.toPlainString();
    }

    private void assertSameCurrency(Money other) {
        Objects.requireNonNull(other, "Operand money must not be null");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: cannot combine " + this.currency + " and " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount.compareTo(money.amount) == 0 && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return currency + " " + amount.toPlainString();
    }
}
