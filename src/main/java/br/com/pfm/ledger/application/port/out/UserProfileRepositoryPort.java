package br.com.pfm.ledger.application.port.out;

import br.com.pfm.ledger.domain.CategoryBudgetRule;
import br.com.pfm.ledger.domain.FamilyMemberProfile;
import br.com.pfm.ledger.domain.LgpdErasureRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound Port for Profile, Family Members, Budgets & LGPD Records (US-13).
 */
public interface UserProfileRepositoryPort {

    Optional<FamilyMemberProfile> findMemberBySubject(UUID tenantId, String userSubject);

    Optional<FamilyMemberProfile> findMemberById(UUID tenantId, UUID memberId);

    FamilyMemberProfile saveMember(FamilyMemberProfile member);

    List<FamilyMemberProfile> listMembersByTenantId(UUID tenantId);

    boolean removeMember(UUID tenantId, UUID memberId);

    List<CategoryBudgetRule> listCategoryBudgetsByTenantId(UUID tenantId);

    CategoryBudgetRule saveCategoryBudget(CategoryBudgetRule budget);

    void saveCardPdfPassword(UUID tenantId, UUID cardId, byte[] encryptedPassword, String keyId);

    LgpdErasureRecord saveLgpdErasureRecord(LgpdErasureRecord record);

    void purgeTenantDataForCryptoShredding(UUID tenantId);
}
