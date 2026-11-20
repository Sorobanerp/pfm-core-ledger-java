package br.com.pfm.ledger.domain;

/**
 * Subscription Lifecycle Status (US-12).
 */
public enum SubscriptionStatus {
    TRIAL(true, true),
    ACTIVE(true, true),
    PAST_DUE(true, true),
    SUSPENDED(false, true);

    private final boolean ingestionAllowed;
    private final boolean readOnlyAllowed;

    SubscriptionStatus(boolean ingestionAllowed, boolean readOnlyAllowed) {
        this.ingestionAllowed = ingestionAllowed;
        this.readOnlyAllowed = readOnlyAllowed;
    }

    public boolean isIngestionAllowed() {
        return ingestionAllowed;
    }

    public boolean isReadOnlyAllowed() {
        return readOnlyAllowed;
    }
}
