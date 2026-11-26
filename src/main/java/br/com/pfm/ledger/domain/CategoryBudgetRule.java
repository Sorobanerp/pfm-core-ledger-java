package br.com.pfm.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Entity representing a Category Budget Rule with Threshold Alerts (US-13).
 */
public final class CategoryBudgetRule {

    private final UUID id;
    private final UUID tenantId;
    private final String categoryId;
    private final String categoryName;
    private Money monthlyCap;
    private int alertThresholdPercentage;
    private final Instant createdAt;
    private Instant updatedAt;

    public CategoryBudgetRule(
            UUID id,
            UUID tenantId,
            String categoryId,
            String categoryName,
            Money monthlyCap,
            int alertThresholdPercentage,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId must not be null");
        this.categoryName = Objects.requireNonNull(categoryName, "categoryName must not be null");
        this.monthlyCap = Objects.requireNonNull(monthlyCap, "monthlyCap must not be null");
        this.alertThresholdPercentage = Math.max(1, Math.min(100, alertThresholdPercentage));
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public void updateCap(Money newCap, int newThreshold) {
        this.monthlyCap = Objects.requireNonNull(newCap, "newCap must not be null");
        this.alertThresholdPercentage = Math.max(1, Math.min(100, newThreshold));
        this.updatedAt = Instant.now();
    }

    public boolean isAlertTriggered(Money currentSpent) {
        if (monthlyCap.isZero() || monthlyCap.isNegative()) {
            return false;
        }
        java.math.BigDecimal ratio = java.math.BigDecimal.valueOf(alertThresholdPercentage)
                .divide(java.math.BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_EVEN);
        Money thresholdAmount = monthlyCap.multiply(ratio);
        return currentSpent.compareTo(thresholdAmount) >= 0;
    }

    public boolean isOverBudget(Money currentSpent) {
        return currentSpent.compareTo(monthlyCap) > 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public Money getMonthlyCap() {
        return monthlyCap;
    }

    public int getAlertThresholdPercentage() {
        return alertThresholdPercentage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
