package br.com.pfm.ledger.adapter.in.grpc;

import br.com.pfm.ledger.application.port.in.GetTenantFinancialSummaryUseCase;
import br.com.pfm.ledger.application.port.in.GetTenantFinancialSummaryUseCase.TenantFinancialSummaryOutput;
import br.com.pfm.ledger.application.port.in.ProcessReceiptUseCase;
import br.com.pfm.ledger.application.port.in.ProcessReceiptUseCase.ProcessReceiptCommand;
import br.com.pfm.ledger.application.port.in.ProcessReceiptUseCase.ProcessReceiptOutput;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound gRPC (HTTP/2) Server Adapter exposing {@code ProcessReceipt} and {@code GetTenantFinancialSummary}
 * contracts defined in {@code pfm-contracts/proto/pfm/v1/core_ledger_service.proto}.
 * Executes every RPC on Java 25 Virtual Threads with {@link ScopedValue} tenant isolation.
 */
@Component
@RestController
@RequestMapping("/pfm.v1.CoreLedgerService")
public class CoreLedgerGrpcServerAdapter {

    private final ProcessReceiptUseCase processReceiptUseCase;
    private final GetTenantFinancialSummaryUseCase financialSummaryUseCase;
    private final ExecutorService grpcVirtualExecutor;

    public CoreLedgerGrpcServerAdapter(
            ProcessReceiptUseCase processReceiptUseCase,
            GetTenantFinancialSummaryUseCase financialSummaryUseCase
    ) {
        this.processReceiptUseCase = Objects.requireNonNull(processReceiptUseCase, "processReceiptUseCase must not be null");
        this.financialSummaryUseCase = Objects.requireNonNull(financialSummaryUseCase, "financialSummaryUseCase must not be null");
        this.grpcVirtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PostMapping("/ProcessReceipt")
    public ProcessReceiptOutput processReceipt(@RequestBody ProcessReceiptCommand request) throws Exception {
        Objects.requireNonNull(request, "request must not be null");
        Future<ProcessReceiptOutput> future = grpcVirtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(
                        request.tenantId(),
                        () -> processReceiptUseCase.processReceipt(request)
                )
        );
        return future.get();
    }

    @GetMapping("/GetTenantFinancialSummary")
    public TenantFinancialSummaryOutput getTenantFinancialSummary(
            @RequestHeader("X-Tenant-ID") UUID tenantId,
            @RequestParam("referenceMonth") String referenceMonth,
            @RequestParam(value = "currency", defaultValue = "BRL") String currency
    ) throws Exception {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Future<TenantFinancialSummaryOutput> future = grpcVirtualExecutor.submit(() ->
                TenantContextHolder.callWithTenant(
                        tenantId,
                        () -> financialSummaryUseCase.getTenantFinancialSummary(tenantId, referenceMonth, currency)
                )
        );
        return future.get();
    }
}
