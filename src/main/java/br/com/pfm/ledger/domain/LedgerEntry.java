package br.com.pfm.ledger.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Entity representing an immutable double-entry / accrual-vs-cash Ledger Entry (RN01, RN03, RN06).
 * Zero external framework imports.
 */
public final class LedgerEntry {

    public enum EntryDirection {
        DEBIT,
        CREDIT
    }

    public enum EntryType {
        STANDARD_EXPENSE,
        STANDARD_INCOME,
        INSTALLMENT_ACCRUAL,
        INVOICE_CASH_SETTLEMENT,
        REFUND_CREDIT,
        TRANSFER_NEUTRAL
    }

    public enum ReconciliationStatus {
        PENDING,
        CONCILED,
        MANUAL_REVIEW,
        CANCELLED_BY_REFUND
    }

    private final UUID id;
    private final UUID tenantId;
    private final UUID accountId;
    private final UUID creditCardId;
    private final String categoryCode;
    private final String subcategoryCode;
    private final EntryDirection direction;
    private final EntryType entryType;
    private final Money amount;
    private final LocalDate competenceDate;
    private final LocalDate cashDate;
    private final String description;
    private final String idempotencyKey;
    private final UUID originalTransactionId;
    private final ReconciliationStatus reconciliationStatus;
    private final Instant createdAt;

    public LedgerEntry(
            UUID id,
            UUID tenantId,
            UUID accountId,
            UUID creditCardId,
            String categoryCode,
            String subcategoryCode,
            EntryDirection direction,
            EntryType entryType,
            Money amount,
            LocalDate competenceDate,
            LocalDate cashDate,
            String description,
            String idempotencyKey,
            UUID originalTransactionId,
            ReconciliationStatus reconciliationStatus,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.creditCardId = creditCardId;
        this.categoryCode = Objects.requireNonNull(categoryCode, "categoryCode must not be null");
        this.subcategoryCode = Objects.requireNonNull(subcategoryCode, "subcategoryCode must not be null");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.entryType = Objects.requireNonNull(entryType, "entryType must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("LedgerEntry amount must be strictly positive");
        }
        this.competenceDate = Objects.requireNonNull(competenceDate, "competenceDate (RN01) must not be null");
        this.cashDate = cashDate;
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.originalTransactionId = originalTransactionId;
        this.reconciliationStatus = Objects.requireNonNull(reconciliationStatus, "reconciliationStatus must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID creditCardId() {
        return creditCardId;
    }

    public String categoryCode() {
        return categoryCode;
    }

    public String subcategoryCode() {
        return subcategoryCode;
    }

    public EntryDirection direction() {
        return direction;
    }

    public EntryType entryType() {
        return entryType;
    }

    public Money amount() {
        return amount;
    }

    public LocalDate competenceDate() {
        return competenceDate;
    }

    public LocalDate cashDate() {
        return cashDate;
    }

    public String description() {
        return description;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public UUID originalTransactionId() {
        return originalTransactionId;
    }

    public ReconciliationStatus reconciliationStatus() {
        return reconciliationStatus;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
