package br.com.pfm.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Entity representing a Family Member Profile (US-13).
 */
public final class FamilyMemberProfile {

    private final UUID id;
    private final UUID tenantId;
    private final String userSubject;
    private final String email;
    private String fullName;
    private FamilyRole role;
    private Money spendingAllowance;
    private String status;
    private String invitationToken;
    private Instant invitationExpiresAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public FamilyMemberProfile(
            UUID id,
            UUID tenantId,
            String userSubject,
            String email,
            String fullName,
            FamilyRole role,
            Money spendingAllowance,
            String status,
            String invitationToken,
            Instant invitationExpiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.userSubject = Objects.requireNonNull(userSubject, "userSubject must not be null");
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.fullName = fullName != null ? fullName : "";
        this.role = role != null ? role : FamilyRole.MEMBER;
        this.spendingAllowance = spendingAllowance != null ? spendingAllowance : Money.of("0.0000", "BRL");
        this.status = status != null ? status : "ACTIVE";
        this.invitationToken = invitationToken;
        this.invitationExpiresAt = invitationExpiresAt;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public void updateProfile(String newFullName) {
        if (newFullName != null && !newFullName.isBlank()) {
            this.fullName = newFullName.trim();
            this.updatedAt = Instant.now();
        }
    }

    public void updateRole(FamilyRole newRole) {
        this.role = Objects.requireNonNull(newRole, "role must not be null");
        this.updatedAt = Instant.now();
    }

    public void updateSpendingAllowance(Money newAllowance) {
        this.spendingAllowance = Objects.requireNonNull(newAllowance, "spendingAllowance must not be null");
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getUserSubject() {
        return userSubject;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public FamilyRole getRole() {
        return role;
    }

    public Money getSpendingAllowance() {
        return spendingAllowance;
    }

    public String getStatus() {
        return status;
    }

    public String getInvitationToken() {
        return invitationToken;
    }

    public Instant getInvitationExpiresAt() {
        return invitationExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
