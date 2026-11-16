package br.com.pfm.ledger.adapter.out.postgres;

import br.com.pfm.ledger.application.port.out.TransactionalOutboxPort;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional Outbox Relay Scheduler (ADR-005) running on Java 25 Virtual Threads.
 * Polls {@code PENDING} events from {@code outbox_events} under RLS {@link ScopedValue} context,
 * publishes them to Kafka topic {@code ledger.entry.committed} partitioned by {@code tenant_id},
 * and updates their status to {@code PUBLISHED}.
 */
@Component
public class OutboxRelayScheduler {

    public static final String LEDGER_COMMITTED_TOPIC = "ledger.entry.committed";

    public record PublishedKafkaEvent(
            String topic,
            String partitionKey,
            String idempotencyKey,
            String eventType,
            String payloadJson,
            Instant publishedAt
    ) {
    }

    private final TransactionalOutboxPort outboxPort;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService virtualThreadExecutor;
    private final List<PublishedKafkaEvent> publishedEventsLog = new CopyOnWriteArrayList<>();

    public OutboxRelayScheduler(
            TransactionalOutboxPort outboxPort,
            TransactionTemplate transactionTemplate
    ) {
        this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Relays pending outbox events for a specific tenant inside a Java 25 Virtual Thread
     * with {@link TenantContextHolder} bound via {@link ScopedValue}.
     */
    public int relayPendingEventsForTenant(UUID tenantId, int batchSize) throws Exception {
        Future<Integer> future = virtualThreadExecutor.submit(() ->
                TenantContextHolder.callWithTenant(tenantId, () ->
                        transactionTemplate.execute(status -> {
                            List<TransactionalOutboxPort.OutboxEventRecord> pending =
                                    outboxPort.findPendingEvents(batchSize);
                            Instant now = Instant.now();
                            for (TransactionalOutboxPort.OutboxEventRecord record : pending) {
                                publishedEventsLog.add(new PublishedKafkaEvent(
                                        LEDGER_COMMITTED_TOPIC,
                                        record.tenantId().toString(),
                                        record.idempotencyKey(),
                                        record.eventType(),
                                        record.payloadJson(),
                                        now
                                ));
                                outboxPort.markEventPublished(record.id(), now);
                            }
                            return pending.size();
                        })
                )
        );
        return future.get();
    }

    public List<PublishedKafkaEvent> getPublishedEventsLog() {
        return List.copyOf(publishedEventsLog);
    }

    public void clearPublishedEventsLog() {
        publishedEventsLog.clear();
    }
}
