package br.com.pfm.ledger.application.port.in;

import br.com.pfm.ledger.domain.CategoryBudgetRule;
import br.com.pfm.ledger.domain.FamilyMemberProfile;
import br.com.pfm.ledger.domain.FamilyRole;
import br.com.pfm.ledger.domain.LgpdErasureRecord;
import br.com.pfm.ledger.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inbound Port for User Profile, Family Management, Category Budgets & LGPD (US-13).
 */
public interface UserProfileUseCase {

    record ExportDataResult(
            UUID tenantId,
            String exportId,
            String downloadUrl,
            Instant expiresAt,
            String checksumSha256
    ) {}

    FamilyMemberProfile getUserProfile(UUID tenantId, String userSubject);

    FamilyMemberProfile updateUserProfile(UUID tenantId, String userSubject, String fullName);

    List<FamilyMemberProfile> listFamilyMembers(UUID tenantId);

    FamilyMemberProfile inviteFamilyMember(UUID tenantId, String email, String fullName, FamilyRole role, Money allowance);

    boolean removeFamilyMember(UUID tenantId, UUID memberId);

    List<CategoryBudgetRule> getCategoryBudgets(UUID tenantId);

    CategoryBudgetRule setCategoryBudget(UUID tenantId, String categoryId, String categoryName, Money monthlyCap, int thresholdPercentage);

    boolean setCardPdfPassword(UUID tenantId, UUID cardId, String password);

    ExportDataResult exportUserData(UUID tenantId, UUID userId, String format);

    LgpdErasureRecord requestCryptoShredding(UUID tenantId, UUID userId, String confirmationPhrase, String mfaToken);
}
