package br.com.pfm.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Entity representing an LGPD Crypto-Shredding Request & Audit Trail (US-13).
 */
public final class LgpdErasureRecord {

    public static final String REQUIRED_CONFIRMATION = "EXCLUIR_DEFINITIVAMENTE_MINHA_CONTA";

    private final UUID id;
    private final UUID tenantId;
    private final UUID userId;
    private final String confirmationPhrase;
    private String status;
    private String destroyedKeyId;
    private Instant shreddedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public LgpdErasureRecord(
            UUID id,
            UUID tenantId,
            UUID userId,
            String confirmationPhrase,
            String status,
            String destroyedKeyId,
            Instant shreddedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.confirmationPhrase = Objects.requireNonNull(confirmationPhrase, "confirmationPhrase must not be null");
        this.status = status != null ? status : "PENDING";
        this.destroyedKeyId = destroyedKeyId;
        this.shreddedAt = shreddedAt;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public static LgpdErasureRecord initiate(UUID tenantId, UUID userId, String confirmationPhrase) {
        if (!REQUIRED_CONFIRMATION.equals(confirmationPhrase)) {
            throw new IllegalArgumentException("Confirmation phrase does not match: " + REQUIRED_CONFIRMATION);
        }
        Instant now = Instant.now();
        return new LgpdErasureRecord(
                UUID.randomUUID(),
                tenantId,
                userId,
                confirmationPhrase,
                "PENDING",
                null,
                null,
                now,
                now
        );
    }

    public void markShredded(String destroyedKeyId) {
        this.destroyedKeyId = Objects.requireNonNull(destroyedKeyId, "destroyedKeyId must not be null");
        this.status = "CRYPTO_SHREDDED";
        this.shreddedAt = Instant.now();
        this.updatedAt = this.shreddedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getConfirmationPhrase() {
        return confirmationPhrase;
    }

    public String getStatus() {
        return status;
    }

    public String getDestroyedKeyId() {
        return destroyedKeyId;
    }

    public Instant getShreddedAt() {
        return shreddedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
