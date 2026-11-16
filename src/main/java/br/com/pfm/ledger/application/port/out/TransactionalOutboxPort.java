package br.com.pfm.ledger.application.port.out;

import br.com.pfm.ledger.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbound Port for the Transactional Outbox Pattern (ADR-005).
 * Persists domain events in the same local ACID transaction as the LedgerEntry.
 */
public interface TransactionalOutboxPort {

    record OutboxEventRecord(
            UUID id,
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Money monetaryAmount,
            String idempotencyKey,
            String payloadJson,
            String status,
            Instant createdAt,
            Instant publishedAt
    ) {
    }

    void appendOutboxEvent(
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String idempotencyKey,
            String payloadJson
    );

    default void appendOutboxEvent(
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Money monetaryAmount,
            String idempotencyKey,
            String payloadJson
    ) {
        appendOutboxEvent(tenantId, aggregateType, aggregateId, eventType, idempotencyKey, payloadJson);
    }

    default List<OutboxEventRecord> findPendingEvents(int limit) {
        return List.of();
    }

    default void markEventPublished(UUID eventId, Instant publishedAt) {
    }
}
