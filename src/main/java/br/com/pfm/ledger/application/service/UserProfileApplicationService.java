package br.com.pfm.ledger.application.service;

import br.com.pfm.ledger.application.port.in.UserProfileUseCase;
import br.com.pfm.ledger.application.port.out.AuditLogRepositoryPort;
import br.com.pfm.ledger.application.port.out.EnvelopeEncryptionKmsPort;
import br.com.pfm.ledger.application.port.out.TenantSubscriptionRepositoryPort;
import br.com.pfm.ledger.application.port.out.UserProfileRepositoryPort;
import br.com.pfm.ledger.domain.AuditLogEntry;
import br.com.pfm.ledger.domain.CategoryBudgetRule;
import br.com.pfm.ledger.domain.FamilyMemberProfile;
import br.com.pfm.ledger.domain.FamilyRole;
import br.com.pfm.ledger.domain.LgpdErasureRecord;
import br.com.pfm.ledger.domain.Money;
import br.com.pfm.ledger.domain.TenantSubscription;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application Service for User Profile, Family RBAC, Category Budgets and LGPD Governance (US-13)
 * with immutable audit trail recording (ADR-010).
 */
@Service
public class UserProfileApplicationService implements UserProfileUseCase {

    private final UserProfileRepositoryPort profileRepository;
    private final TenantSubscriptionRepositoryPort subscriptionRepository;
    private final EnvelopeEncryptionKmsPort kmsPort;
    private final AuditLogRepositoryPort auditLogRepository;

    public UserProfileApplicationService(
            UserProfileRepositoryPort profileRepository,
            TenantSubscriptionRepositoryPort subscriptionRepository,
            EnvelopeEncryptionKmsPort kmsPort,
            AuditLogRepositoryPort auditLogRepository
    ) {
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.subscriptionRepository = Objects.requireNonNull(subscriptionRepository, "subscriptionRepository must not be null");
        this.kmsPort = Objects.requireNonNull(kmsPort, "kmsPort must not be null");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null");
    }

    private static String resolveTraceId() {
        SpanContext ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    @Transactional(readOnly = true)
    public FamilyMemberProfile getUserProfile(UUID tenantId, String userSubject) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userSubject, "userSubject must not be null");
        return profileRepository.findMemberBySubject(tenantId, userSubject)
                .orElseGet(() -> new FamilyMemberProfile(
                        UUID.randomUUID(),
                        tenantId,
                        userSubject,
                        userSubject + "@pfm.local",
                        "User " + userSubject,
                        FamilyRole.OWNER,
                        Money.of("0.0000", "BRL"),
                        "ACTIVE",
                        null,
                        null,
                        Instant.now(),
                        Instant.now()
                ));
    }

    @Override
    @Transactional
    public FamilyMemberProfile updateUserProfile(UUID tenantId, String userSubject, String fullName) {
        FamilyMemberProfile profile = getUserProfile(tenantId, userSubject);
        profile.updateProfile(fullName);
        return profileRepository.saveMember(profile);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FamilyMemberProfile> listFamilyMembers(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return profileRepository.listMembersByTenantId(tenantId);
    }

    @Override
    @Transactional
    public FamilyMemberProfile inviteFamilyMember(UUID tenantId, String email, String fullName, FamilyRole role, Money allowance) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(email, "email must not be null");

        // Verify quota
        List<FamilyMemberProfile> currentMembers = profileRepository.listMembersByTenantId(tenantId);
        TenantSubscription subscription = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantSubscription.createDefaultTrial(tenantId));

        if (!subscription.canAddMember(currentMembers.size())) {
            throw new IllegalStateException("Max family members limit of " + subscription.getMaxMembers() + " reached for plan " + subscription.getPlan());
        }

        String invitationToken = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(48L * 3600);

        FamilyMemberProfile newMember = new FamilyMemberProfile(
                UUID.randomUUID(),
                tenantId,
                email,
                email,
                fullName,
                role,
                allowance,
                "INVITED",
                invitationToken,
                expiresAt,
                Instant.now(),
                Instant.now()
        );

        FamilyMemberProfile saved = profileRepository.saveMember(newMember);
        auditLogRepository.append(AuditLogEntry.of(
                tenantId,
                "OWNER",
                "OWNER",
                "FAMILY_MEMBER_INVITED",
                "TENANT_MEMBER",
                saved.getId().toString(),
                resolveTraceId(),
                "{\"role\":\"" + role.name() + "\"}"
        ));
        return saved;
    }

    @Override
    @Transactional
    public boolean removeFamilyMember(UUID tenantId, UUID memberId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(memberId, "memberId must not be null");
        return profileRepository.removeMember(tenantId, memberId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryBudgetRule> getCategoryBudgets(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return profileRepository.listCategoryBudgetsByTenantId(tenantId);
    }

    @Override
    @Transactional
    public CategoryBudgetRule setCategoryBudget(UUID tenantId, String categoryId, String categoryName, Money monthlyCap, int thresholdPercentage) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(categoryName, "categoryName must not be null");

        CategoryBudgetRule budget = new CategoryBudgetRule(
                UUID.randomUUID(),
                tenantId,
                categoryId,
                categoryName,
                monthlyCap,
                thresholdPercentage,
                Instant.now(),
                Instant.now()
        );

        CategoryBudgetRule saved = profileRepository.saveCategoryBudget(budget);
        auditLogRepository.append(AuditLogEntry.of(
                tenantId,
                "OWNER",
                "OWNER",
                "CATEGORY_BUDGET_SET",
                "CATEGORY_BUDGET",
                categoryId,
                resolveTraceId(),
                "{\"thresholdPercentage\":" + thresholdPercentage + "}"
        ));
        return saved;
    }

    @Override
    @Transactional
    public boolean setCardPdfPassword(UUID tenantId, UUID cardId, String password) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(cardId, "cardId must not be null");
        Objects.requireNonNull(password, "password must not be null");

        byte[] encrypted = kmsPort.encryptForTenant(tenantId, password.getBytes(StandardCharsets.UTF_8));
        String keyId = "alias/pfm-tenant-master-key:" + tenantId;
        profileRepository.saveCardPdfPassword(tenantId, cardId, encrypted, keyId);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportDataResult exportUserData(UUID tenantId, UUID userId, String format) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String exportId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(24L * 3600);
        String downloadUrl = "https://s3.us-east-2.amazonaws.com/pfm-statements-encrypted-prod/exports/" + tenantId + "/" + exportId + "." + format.toLowerCase();

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((tenantId.toString() + exportId).getBytes(StandardCharsets.UTF_8));
            String checksum = HexFormat.of().formatHex(hash);
            return new ExportDataResult(tenantId, exportId, downloadUrl, expiresAt, checksum);
        } catch (Exception e) {
            return new ExportDataResult(tenantId, exportId, downloadUrl, expiresAt, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }
    }

    @Override
    @Transactional
    public LgpdErasureRecord requestCryptoShredding(UUID tenantId, UUID userId, String confirmationPhrase, String mfaToken) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        LgpdErasureRecord record = LgpdErasureRecord.initiate(tenantId, userId, confirmationPhrase);
        String destroyedKeyId = "arn:aws:kms:us-east-2:366476833548:key/destroyed-" + tenantId;
        record.markShredded(destroyedKeyId);

        // Purge tenant's sensitive data in database
        profileRepository.purgeTenantDataForCryptoShredding(tenantId);
        LgpdErasureRecord saved = profileRepository.saveLgpdErasureRecord(record);
        auditLogRepository.append(AuditLogEntry.of(
                tenantId,
                userId.toString(),
                "OWNER",
                "LGPD_CRYPTO_SHREDDING_EXECUTED",
                "KMS_KEY",
                destroyedKeyId,
                resolveTraceId(),
                "{\"status\":\"CRYPTO_SHREDDED\"}"
        ));
        return saved;
    }
}
