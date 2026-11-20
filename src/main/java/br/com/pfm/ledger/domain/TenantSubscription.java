package br.com.pfm.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Aggregate representing a Tenant's SaaS Subscription (US-12).
 */
public final class TenantSubscription {

    private final UUID id;
    private final UUID tenantId;
    private SubscriptionPlan plan;
    private SubscriptionStatus status;
    private int maxMembers;
    private int maxCards;
    private int monthlyPdfQuota;
    private int usedPdfQuota;
    private Instant trialEndsAt;
    private Instant currentPeriodEnd;
    private final Instant createdAt;
    private Instant updatedAt;

    public TenantSubscription(
            UUID id,
            UUID tenantId,
            SubscriptionPlan plan,
            SubscriptionStatus status,
            int maxMembers,
            int maxCards,
            int monthlyPdfQuota,
            int usedPdfQuota,
            Instant trialEndsAt,
            Instant currentPeriodEnd,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.maxMembers = Math.max(1, maxMembers);
        this.maxCards = Math.max(1, maxCards);
        this.monthlyPdfQuota = Math.max(0, monthlyPdfQuota);
        this.usedPdfQuota = Math.max(0, usedPdfQuota);
        this.trialEndsAt = trialEndsAt;
        this.currentPeriodEnd = currentPeriodEnd;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public static TenantSubscription createDefaultTrial(UUID tenantId) {
        Instant now = Instant.now();
        SubscriptionPlan defaultPlan = SubscriptionPlan.FREE;
        return new TenantSubscription(
                UUID.randomUUID(),
                tenantId,
                defaultPlan,
                SubscriptionStatus.TRIAL,
                defaultPlan.getDefaultMaxMembers(),
                defaultPlan.getDefaultMaxCards(),
                defaultPlan.getDefaultMonthlyPdfQuota(),
                0,
                now.plusSeconds(14L * 24 * 3600),
                now.plusSeconds(30L * 24 * 3600),
                now,
                now
        );
    }

    public void changePlan(SubscriptionPlan newPlan) {
        Objects.requireNonNull(newPlan, "newPlan must not be null");
        this.plan = newPlan;
        this.maxMembers = newPlan.getDefaultMaxMembers();
        this.maxCards = newPlan.getDefaultMaxCards();
        this.monthlyPdfQuota = newPlan.getDefaultMonthlyPdfQuota();
        this.updatedAt = Instant.now();
    }

    public void updateStatus(SubscriptionStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public void consumePdfQuota() {
        if (!status.isIngestionAllowed()) {
            throw new IllegalStateException("Cannot ingest PDF: subscription is " + status);
        }
        if (usedPdfQuota >= monthlyPdfQuota) {
            throw new IllegalStateException("Monthly PDF quota of " + monthlyPdfQuota + " exceeded for plan " + plan);
        }
        this.usedPdfQuota++;
        this.updatedAt = Instant.now();
    }

    public boolean canAddMember(int currentMemberCount) {
        return currentMemberCount < maxMembers;
    }

    public boolean canAddCard(int currentCardCount) {
        return currentCardCount < maxCards;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public SubscriptionPlan getPlan() {
        return plan;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public int getMaxMembers() {
        return maxMembers;
    }

    public int getMaxCards() {
        return maxCards;
    }

    public int getMonthlyPdfQuota() {
        return monthlyPdfQuota;
    }

    public int getUsedPdfQuota() {
        return usedPdfQuota;
    }

    public Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
