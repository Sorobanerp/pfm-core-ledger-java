package br.com.pfm.ledger.application.service;

import br.com.pfm.ledger.application.port.in.TenantAdminUseCase;
import br.com.pfm.ledger.application.port.out.AuditLogRepositoryPort;
import br.com.pfm.ledger.application.port.out.TenantSubscriptionRepositoryPort;
import br.com.pfm.ledger.domain.AuditLogEntry;
import br.com.pfm.ledger.domain.SubscriptionPlan;
import br.com.pfm.ledger.domain.SubscriptionStatus;
import br.com.pfm.ledger.domain.TenantSubscription;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application Service orchestrating SaaS Tenant Administration & Subscription Lifecycle (US-12)
 * with immutable audit trail recording (ADR-010).
 */
@Service
public class TenantAdminApplicationService implements TenantAdminUseCase {

    private final TenantSubscriptionRepositoryPort subscriptionRepository;
    private final AuditLogRepositoryPort auditLogRepository;

    public TenantAdminApplicationService(
            TenantSubscriptionRepositoryPort subscriptionRepository,
            AuditLogRepositoryPort auditLogRepository
    ) {
        this.subscriptionRepository = Objects.requireNonNull(subscriptionRepository, "subscriptionRepository must not be null");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null");
    }

    private static String resolveTraceId() {
        SpanContext ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantSummaryDTO> listTenants(int pageSize, String pageToken, String statusFilter, String planFilter) {
        return subscriptionRepository.listTenantsForAdmin(pageSize, pageToken, statusFilter, planFilter);
    }

    @Override
    @Transactional
    public TenantSummaryDTO changeTenantPlan(UUID tenantId, SubscriptionPlan newPlan) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(newPlan, "newPlan must not be null");

        TenantSubscription subscription = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantSubscription.createDefaultTrial(tenantId));

        subscription.changePlan(newPlan);
        subscriptionRepository.saveSubscription(subscription);
        subscriptionRepository.updateTenantPlan(tenantId, newPlan);
        auditLogRepository.append(AuditLogEntry.of(
                tenantId,
                "PLATFORM_ADMIN",
                "PLATFORM_ADMIN",
                "TENANT_PLAN_CHANGED",
                "SUBSCRIPTION",
                tenantId.toString(),
                resolveTraceId(),
                "{\"newPlan\":\"" + newPlan.name() + "\"}"
        ));

        return subscriptionRepository.findTenantSummary(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found after plan update: " + tenantId));
    }

    @Override
    @Transactional
    public TenantSummaryDTO updateSubscriptionStatus(UUID tenantId, SubscriptionStatus newStatus, String reason) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");

        TenantSubscription subscription = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantSubscription.createDefaultTrial(tenantId));

        subscription.updateStatus(newStatus);
        subscriptionRepository.saveSubscription(subscription);
        subscriptionRepository.updateSubscriptionStatus(tenantId, newStatus, reason);
        auditLogRepository.append(AuditLogEntry.of(
                tenantId,
                "PLATFORM_ADMIN",
                "PLATFORM_ADMIN",
                "SUBSCRIPTION_STATUS_UPDATED",
                "SUBSCRIPTION",
                tenantId.toString(),
                resolveTraceId(),
                "{\"newStatus\":\"" + newStatus.name() + "\"}"
        ));

        return subscriptionRepository.findTenantSummary(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found after status update: " + tenantId));
    }

    @Override
    @Transactional
    public TenantSummaryDTO createTenant(UUID tenantId, String tenantName, String ownerEmail, SubscriptionPlan plan) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        SubscriptionPlan effectivePlan = plan != null ? plan : SubscriptionPlan.FAMILY_PLUS;
        String effectiveName = (tenantName != null && !tenantName.isBlank()) ? tenantName : "Familia PFM";

        subscriptionRepository.bootstrapTenant(tenantId, effectiveName, ownerEmail, effectivePlan);
        TenantSubscription subscription = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantSubscription.createDefaultTrial(tenantId));
        subscription.changePlan(effectivePlan);
        subscription.updateStatus(SubscriptionStatus.ACTIVE);
        subscriptionRepository.saveSubscription(subscription);
        subscriptionRepository.updateTenantPlan(tenantId, effectivePlan);

        auditLogRepository.append(AuditLogEntry.of(
                tenantId,
                "PLATFORM_ADMIN",
                "PLATFORM_ADMIN",
                "TENANT_CREATED",
                "TENANT",
                tenantId.toString(),
                resolveTraceId(),
                "{\"tenantName\":\"" + effectiveName + "\",\"plan\":\"" + effectivePlan.name() + "\"}"
        ));

        return subscriptionRepository.findTenantSummary(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found after creation: " + tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public PlatformMetricsDTO getPlatformMetrics(String period) {
        return subscriptionRepository.calculatePlatformMetrics();
    }
}
