package br.com.pfm.ledger.adapter.in.kafka;

import br.com.pfm.ledger.application.port.in.CommitCategorizedTransactionUseCase;
import br.com.pfm.ledger.application.port.in.CommitCategorizedTransactionUseCase.CategorizedTransactionCommand;
import br.com.pfm.ledger.application.port.in.CommitCategorizedTransactionUseCase.CommitTransactionResult;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka Consumer Adapter for topic {@code tx.categorized}.
 * Dispatches each message on a Java 25 Virtual Thread and binds {@code tenant_id}
 * to {@link TenantContextHolder#CURRENT_TENANT} via {@link ScopedValue}.
 */
@Component
public class CategorizedTransactionKafkaConsumer {

    public static final String TOPIC_TX_CATEGORIZED = "tx.categorized";

    private final CommitCategorizedTransactionUseCase commitUseCase;
    private final ExecutorService virtualThreadExecutor;

    public CategorizedTransactionKafkaConsumer(CommitCategorizedTransactionUseCase commitUseCase) {
        this.commitUseCase = Objects.requireNonNull(commitUseCase, "commitUseCase must not be null");
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Consumes a {@code tx.categorized} event inside a dedicated Virtual Thread with
     * {@link ScopedValue} bound to the message's {@code tenant_id}.
     */
    public CommitTransactionResult consumeCategorizedTransaction(CategorizedTransactionCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Future<CommitTransactionResult> future = virtualThreadExecutor.submit(() -> {
            if (!Thread.currentThread().isVirtual()) {
                throw new IllegalStateException("Expected execution on a Java 25 Virtual Thread");
            }
            return TenantContextHolder.callWithTenant(
                    command.tenantId(),
                    () -> commitUseCase.commitCategorizedTransaction(command)
            );
        });
        return future.get();
    }
}
