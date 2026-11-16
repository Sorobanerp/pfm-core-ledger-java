package br.com.pfm.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Entity representing a long-term Financial Contract (SAC, Price, or Consorcio).
 */
public final class FinancialContract {

    public enum Modality {
        SAC,
        PRICE,
        CONSORCIO
    }

    private final UUID id;
    private final UUID tenantId;
    private final String contractName;
    private final Modality modality;
    private final Money principalBalance;
    private final BigDecimal monthlyInterestRate;
    private final int totalInstallments;
    private final int remainingInstallments;
    private final LocalDate startDate;

    public FinancialContract(
            UUID id,
            UUID tenantId,
            String contractName,
            Modality modality,
            Money principalBalance,
            BigDecimal monthlyInterestRate,
            int totalInstallments,
            int remainingInstallments,
            LocalDate startDate
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.contractName = Objects.requireNonNull(contractName, "contractName must not be null");
        this.modality = Objects.requireNonNull(modality, "modality must not be null");
        this.principalBalance = Objects.requireNonNull(principalBalance, "principalBalance must not be null");
        this.monthlyInterestRate = Objects.requireNonNull(monthlyInterestRate, "monthlyInterestRate must not be null")
                .setScale(6, RoundingMode.HALF_EVEN);
        if (totalInstallments < 1 || remainingInstallments < 0 || remainingInstallments > totalInstallments) {
            throw new IllegalArgumentException("Invalid installment counts");
        }
        this.totalInstallments = totalInstallments;
        this.remainingInstallments = remainingInstallments;
        this.startDate = Objects.requireNonNull(startDate, "startDate must not be null");
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String contractName() {
        return contractName;
    }

    public Modality modality() {
        return modality;
    }

    public Money principalBalance() {
        return principalBalance;
    }

    public BigDecimal monthlyInterestRate() {
        return monthlyInterestRate;
    }

    public int totalInstallments() {
        return totalInstallments;
    }

    public int remainingInstallments() {
        return remainingInstallments;
    }

    public LocalDate startDate() {
        return startDate;
    }
}
