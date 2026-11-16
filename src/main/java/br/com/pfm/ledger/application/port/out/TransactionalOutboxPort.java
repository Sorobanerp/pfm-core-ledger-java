package br.com.pfm.ledger.application.port.out;

import java.util.UUID;

/**
 * Outbound Port for the Transactional Outbox Pattern (ADR-005).
 * Persists domain events in the same local ACID transaction as the LedgerEntry.
 */
public interface TransactionalOutboxPort {

    void appendOutboxEvent(
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String idempotencyKey,
            String payloadJson
    );
}
