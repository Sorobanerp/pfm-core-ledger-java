package br.com.pfm.ledger.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Entity representing an individual installment schedule entry (RN01 & RN06).
 */
public final class InstallmentSchedule {

    public enum InstallmentStatus {
        SCHEDULED,
        BILLED,
        SETTLED,
        CANCELLED_BY_REFUND
    }

    private final UUID id;
    private final UUID tenantId;
    private final UUID creditCardId;
    private final UUID parentLedgerEntryId;
    private final int installmentNumber;
    private final int totalInstallments;
    private final String competenceMonth;
    private final LocalDate dueDate;
    private final Money amount;
    private final InstallmentStatus status;

    public InstallmentSchedule(
            UUID id,
            UUID tenantId,
            UUID creditCardId,
            UUID parentLedgerEntryId,
            int installmentNumber,
            int totalInstallments,
            String competenceMonth,
            LocalDate dueDate,
            Money amount,
            InstallmentStatus status
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.creditCardId = Objects.requireNonNull(creditCardId, "creditCardId must not be null");
        this.parentLedgerEntryId = Objects.requireNonNull(parentLedgerEntryId, "parentLedgerEntryId must not be null");
        if (installmentNumber < 1 || installmentNumber > totalInstallments) {
            throw new IllegalArgumentException("Invalid installmentNumber bounds");
        }
        this.installmentNumber = installmentNumber;
        this.totalInstallments = totalInstallments;
        this.competenceMonth = Objects.requireNonNull(competenceMonth, "competenceMonth must not be null");
        this.dueDate = Objects.requireNonNull(dueDate, "dueDate must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID creditCardId() {
        return creditCardId;
    }

    public UUID parentLedgerEntryId() {
        return parentLedgerEntryId;
    }

    public int installmentNumber() {
        return installmentNumber;
    }

    public int totalInstallments() {
        return totalInstallments;
    }

    public String competenceMonth() {
        return competenceMonth;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public Money amount() {
        return amount;
    }

    public InstallmentStatus status() {
        return status;
    }
}
