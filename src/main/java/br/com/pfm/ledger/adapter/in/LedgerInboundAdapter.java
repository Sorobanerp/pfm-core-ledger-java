package br.com.pfm.ledger.adapter.in;

import br.com.pfm.ledger.application.port.in.RecordLedgerEntryUseCase;
import br.com.pfm.ledger.domain.LedgerEntry;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Inbound Adapter (gRPC / Kafka entry point) executing Ledger use cases inside a bound
 * Java 25 {@link ScopedValue} tenant context protected by Resilience4j (ADR-004).
 */
@Component
public class LedgerInboundAdapter {

    private final RecordLedgerEntryUseCase recordLedgerEntryUseCase;

    public LedgerInboundAdapter(RecordLedgerEntryUseCase recordLedgerEntryUseCase) {
        this.recordLedgerEntryUseCase = recordLedgerEntryUseCase;
    }

    @CircuitBreaker(name = "coreLedger")
    @Retry(name = "coreLedger")
    @Bulkhead(name = "coreLedger")
    public LedgerEntry handleRecordEntry(UUID authenticatedTenantId, LedgerEntry entry) throws Exception {
        return TenantContextHolder.callWithTenant(
                authenticatedTenantId,
                () -> recordLedgerEntryUseCase.recordEntry(entry)
        );
    }

    @CircuitBreaker(name = "coreLedger")
    @Bulkhead(name = "coreLedger")
    public List<LedgerEntry> handleQueryTenantEntries(UUID authenticatedTenantId) throws Exception {
        return TenantContextHolder.callWithTenant(
                authenticatedTenantId,
                recordLedgerEntryUseCase::findAllForCurrentTenant
        );
    }
}
