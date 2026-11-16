package br.com.pfm.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Entity representing a Credit Card and its total/available credit limit (RN02).
 * Credit limit is strictly segregated from monthly category budgets.
 */
public final class CreditCard {

    private final UUID id;
    private final UUID tenantId;
    private final UUID financialAccountId;
    private final String cardName;
    private final String cardLastFour;
    private final Money creditLimit;
    private final Money availableLimit;
    private final int closingDay;
    private final int dueDay;

    public CreditCard(
            UUID id,
            UUID tenantId,
            UUID financialAccountId,
            String cardName,
            String cardLastFour,
            Money creditLimit,
            Money availableLimit,
            int closingDay,
            int dueDay
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.financialAccountId = Objects.requireNonNull(financialAccountId, "financialAccountId must not be null");
        this.cardName = Objects.requireNonNull(cardName, "cardName must not be null");
        this.cardLastFour = Objects.requireNonNull(cardLastFour, "cardLastFour must not be null");
        this.creditLimit = Objects.requireNonNull(creditLimit, "creditLimit must not be null");
        this.availableLimit = Objects.requireNonNull(availableLimit, "availableLimit must not be null");
        if (closingDay < 1 || closingDay > 31) {
            throw new IllegalArgumentException("closingDay must be between 1 and 31");
        }
        if (dueDay < 1 || dueDay > 31) {
            throw new IllegalArgumentException("dueDay must be between 1 and 31");
        }
        this.closingDay = closingDay;
        this.dueDay = dueDay;
    }

    /**
     * RN02: A parcelled purchase consumes the full total purchase amount immediately from the available limit.
     */
    public CreditCard consumeLimit(Money totalPurchaseAmount) {
        Money updatedAvailable = this.availableLimit.subtract(totalPurchaseAmount);
        return new CreditCard(
                id,
                tenantId,
                financialAccountId,
                cardName,
                cardLastFour,
                creditLimit,
                updatedAvailable,
                closingDay,
                dueDay
        );
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID financialAccountId() {
        return financialAccountId;
    }

    public String cardName() {
        return cardName;
    }

    public String cardLastFour() {
        return cardLastFour;
    }

    public Money creditLimit() {
        return creditLimit;
    }

    public Money availableLimit() {
        return availableLimit;
    }

    public int closingDay() {
        return closingDay;
    }

    public int dueDay() {
        return dueDay;
    }
}
