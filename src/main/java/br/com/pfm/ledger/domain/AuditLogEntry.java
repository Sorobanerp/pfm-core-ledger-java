package br.com.pfm.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure Domain Value Object / Entity representing an immutable append-only audit trail entry (ADR-010).
 */
public record AuditLogEntry(
        UUID id,
        UUID tenantId,
        String actorId,
        String actorRole,
        String action,
        String resourceType,
        String resourceId,
        String traceId,
        String metadataJson,
        Instant createdAt
) {

    public AuditLogEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        if (actorId.isBlank() || action.isBlank() || traceId.isBlank()) {
            throw new IllegalArgumentException("actorId, action, and traceId must not be blank");
        }
        metadataJson = (metadataJson == null || metadataJson.isBlank()) ? "{}" : metadataJson;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static AuditLogEntry of(
            UUID tenantId,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            String traceId,
            String metadataJson
    ) {
        return new AuditLogEntry(
                UUID.randomUUID(),
                tenantId,
                actorId,
                actorRole,
                action,
                resourceType,
                resourceId,
                traceId,
                metadataJson,
                Instant.now()
        );
    }
}
