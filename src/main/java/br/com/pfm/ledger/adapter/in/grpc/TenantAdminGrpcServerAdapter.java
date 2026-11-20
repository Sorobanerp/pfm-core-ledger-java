package br.com.pfm.ledger.adapter.in.grpc;

import br.com.pfm.ledger.application.port.in.TenantAdminUseCase;
import br.com.pfm.ledger.application.port.in.TenantAdminUseCase.PlatformMetricsDTO;
import br.com.pfm.ledger.application.port.in.TenantAdminUseCase.TenantSummaryDTO;
import br.com.pfm.ledger.domain.SubscriptionPlan;
import br.com.pfm.ledger.domain.SubscriptionStatus;
import br.com.pfm.ledger.infrastructure.TenantContextHolder;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound gRPC / HTTP Server Adapter for SaaS Platform Administration (US-12).
 * Operates on Java 25 Virtual Threads and enforces platform admin context.
 */
@RestController
@RequestMapping("/pfm.v1.TenantAdminService")
public class TenantAdminGrpcServerAdapter {

    private final TenantAdminUseCase adminUseCase;
    private final ExecutorService virtualExecutor;

    public TenantAdminGrpcServerAdapter(TenantAdminUseCase adminUseCase) {
        this.adminUseCase = Objects.requireNonNull(adminUseCase, "adminUseCase must not be null");
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public record ListTenantsRequestCommand(int pageSize, String pageToken, String statusFilter, String planFilter) {}
    public record ListTenantsResponseDTO(List<TenantSummaryDTO> tenants, String nextPageToken, int totalCount) {}

    @PostMapping("/ListTenants")
    public ListTenantsResponseDTO listTenants(@RequestBody(required = false) ListTenantsRequestCommand command) throws Exception {
        int pageSize = command != null ? command.pageSize() : 20;
        String pageToken = command != null ? command.pageToken() : null;
        String statusFilter = command != null ? command.statusFilter() : null;
        String planFilter = command != null ? command.planFilter() : null;

        Future<List<TenantSummaryDTO>> future = virtualExecutor.submit(() ->
                TenantContextHolder.callAsPlatformAdmin(() ->
                        adminUseCase.listTenants(pageSize, pageToken, statusFilter, planFilter)
                )
        );
        List<TenantSummaryDTO> list = future.get();
        return new ListTenantsResponseDTO(list, "", list.size());
    }

    public record ChangeTenantPlanCommand(UUID tenantId, String newPlan) {}

    @PostMapping("/ChangeTenantPlan")
    public TenantSummaryDTO changeTenantPlan(@RequestBody ChangeTenantPlanCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.tenantId(), "tenantId must not be null");
        SubscriptionPlan plan = SubscriptionPlan.valueOf(command.newPlan());

        Future<TenantSummaryDTO> future = virtualExecutor.submit(() ->
                TenantContextHolder.callAsPlatformAdmin(() ->
                        adminUseCase.changeTenantPlan(command.tenantId(), plan)
                )
        );
        return future.get();
    }

    public record UpdateSubscriptionStatusCommand(UUID tenantId, String newStatus, String reason) {}

    @PostMapping("/UpdateSubscriptionStatus")
    public TenantSummaryDTO updateSubscriptionStatus(@RequestBody UpdateSubscriptionStatusCommand command) throws Exception {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.tenantId(), "tenantId must not be null");
        SubscriptionStatus status = SubscriptionStatus.valueOf(command.newStatus());

        Future<TenantSummaryDTO> future = virtualExecutor.submit(() ->
                TenantContextHolder.callAsPlatformAdmin(() ->
                        adminUseCase.updateSubscriptionStatus(command.tenantId(), status, command.reason())
                )
        );
        return future.get();
    }

    public record GetPlatformMetricsCommand(String period) {}

    @PostMapping("/GetPlatformMetrics")
    public PlatformMetricsDTO getPlatformMetrics(@RequestBody(required = false) GetPlatformMetricsCommand command) throws Exception {
        String period = command != null ? command.period() : "ALL";
        Future<PlatformMetricsDTO> future = virtualExecutor.submit(() ->
                TenantContextHolder.callAsPlatformAdmin(() ->
                        adminUseCase.getPlatformMetrics(period)
                )
        );
        return future.get();
    }
}
