package br.com.pfm.ledger.domain;

/**
 * Official SaaS Subscription Plans (US-12).
 * Defines operational quotas and service tiers.
 */
public enum SubscriptionPlan {
    FREE(1, 1, 3),
    INDIVIDUAL_PRO(1, 10, 30),
    FAMILY_PLUS(5, 20, 100);

    private final int defaultMaxMembers;
    private final int defaultMaxCards;
    private final int defaultMonthlyPdfQuota;

    SubscriptionPlan(int defaultMaxMembers, int defaultMaxCards, int defaultMonthlyPdfQuota) {
        this.defaultMaxMembers = defaultMaxMembers;
        this.defaultMaxCards = defaultMaxCards;
        this.defaultMonthlyPdfQuota = defaultMonthlyPdfQuota;
    }

    public int getDefaultMaxMembers() {
        return defaultMaxMembers;
    }

    public int getDefaultMaxCards() {
        return defaultMaxCards;
    }

    public int getDefaultMonthlyPdfQuota() {
        return defaultMonthlyPdfQuota;
    }
}
