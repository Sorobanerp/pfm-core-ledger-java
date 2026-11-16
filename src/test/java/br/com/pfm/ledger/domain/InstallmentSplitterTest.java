package br.com.pfm.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure Domain Unit Tests for {@link InstallmentSplitter} (10x splitting & HALF_EVEN last-installment cent adjustment).
 */
class InstallmentSplitterTest {

    private final InstallmentSplitter splitter = new InstallmentSplitter();

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID CARD_ID = UUID.fromString("33333333-aaaa-4aaa-8aaa-333333333333");
    private static final UUID PARENT_ENTRY_ID = UUID.fromString("44444444-aaaa-4aaa-8aaa-444444444444");

    @Test
    @DisplayName("BDD Parcelamento 10x: Desdobra compra parcelada em 10x SCHEDULED com ajuste exato de centavos na última parcela (HALF_EVEN)")
    void shouldSplitTenInstallmentsWithExactCentAdjustmentOnLastInstallment() {
        // R$ 1.000,03 divided by 10 -> 9 installments of 100.0000 and 10th installment of 100.0300
        Money totalPurchase = Money.brl("1000.0300");

        List<InstallmentSchedule> schedules = splitter.splitPurchase(
                TENANT_ID,
                CARD_ID,
                PARENT_ENTRY_ID,
                totalPurchase,
                1,
                10,
                YearMonth.of(2026, 3),
                10
        );

        assertThat(schedules).hasSize(10);
        assertThat(schedules).allMatch(s -> s.status() == InstallmentSchedule.InstallmentStatus.SCHEDULED);

        for (int i = 0; i < 9; i++) {
            assertThat(schedules.get(i).installmentNumber()).isEqualTo(i + 1);
            assertThat(schedules.get(i).amount()).isEqualTo(Money.brl("100.0000"));
        }

        InstallmentSchedule tenthInstallment = schedules.get(9);
        assertThat(tenthInstallment.installmentNumber()).isEqualTo(10);
        assertThat(tenthInstallment.competenceMonth()).isEqualTo("2026-12");
        assertThat(tenthInstallment.amount()).isEqualTo(Money.brl("100.0300"));

        Money sum = schedules.stream()
                .map(InstallmentSchedule::amount)
                .reduce(Money.zeroBrl(), Money::add);
        assertThat(sum).isEqualTo(totalPurchase);
    }

    @Test
    @DisplayName("Desdobra parcelas restantes a partir de installment_current até installment_total")
    void shouldGenerateRemainingInstallmentsFromCurrentToTotal() {
        Money totalPurchase = Money.brl("1500.0000");

        // Starting from installment 4 of 10 -> generates 7 schedules (04/10 through 10/10)
        List<InstallmentSchedule> schedules = splitter.splitPurchase(
                TENANT_ID,
                CARD_ID,
                PARENT_ENTRY_ID,
                totalPurchase,
                4,
                10,
                YearMonth.of(2026, 6),
                15
        );

        assertThat(schedules).hasSize(7);
        assertThat(schedules.getFirst().installmentNumber()).isEqualTo(4);
        assertThat(schedules.getFirst().competenceMonth()).isEqualTo("2026-06");
        assertThat(schedules.getLast().installmentNumber()).isEqualTo(10);
        assertThat(schedules.getLast().competenceMonth()).isEqualTo("2026-12");
        assertThat(schedules).allMatch(s -> s.amount().equals(Money.brl("150.0000")));
    }
}
