package br.com.pfm.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FinancialContract Domain Entity Tests")
class FinancialContractTest {

    @Test
    void shouldCreateValidSacContract() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Money balance = Money.brl("120000.0000");
        BigDecimal rate = new BigDecimal("0.0085");
        LocalDate startDate = LocalDate.of(2026, 1, 10);

        FinancialContract contract = new FinancialContract(
                id, tenantId, "Financiamento Imobiliario SAC",
                FinancialContract.Modality.SAC, balance, rate, 360, 360, startDate
        );

        assertEquals(id, contract.id());
        assertEquals(tenantId, contract.tenantId());
        assertEquals("Financiamento Imobiliario SAC", contract.contractName());
        assertEquals(FinancialContract.Modality.SAC, contract.modality());
        assertEquals(balance, contract.principalBalance());
        assertEquals(new BigDecimal("0.008500"), contract.monthlyInterestRate());
        assertEquals(360, contract.totalInstallments());
        assertEquals(360, contract.remainingInstallments());
        assertEquals(startDate, contract.startDate());
    }

    @Test
    void shouldCreatePriceAndConsorcioContracts() {
        UUID tenantId = UUID.randomUUID();
        FinancialContract price = new FinancialContract(
                UUID.randomUUID(), tenantId, "Emprestimo Price",
                FinancialContract.Modality.PRICE, Money.brl("50000.0000"),
                new BigDecimal("0.015"), 48, 24, LocalDate.now()
        );
        assertEquals(FinancialContract.Modality.PRICE, price.modality());

        FinancialContract consorcio = new FinancialContract(
                UUID.randomUUID(), tenantId, "Consorcio Auto",
                FinancialContract.Modality.CONSORCIO, Money.brl("80000.0000"),
                BigDecimal.ZERO, 72, 60, LocalDate.now()
        );
        assertEquals(FinancialContract.Modality.CONSORCIO, consorcio.modality());
    }

    @Test
    void shouldThrowOnInvalidInstallmentCounts() {
        UUID tenantId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new FinancialContract(
                UUID.randomUUID(), tenantId, "Invalid",
                FinancialContract.Modality.SAC, Money.brl("1000.00"),
                BigDecimal.ZERO, 0, 0, LocalDate.now()
        ));

        assertThrows(IllegalArgumentException.class, () -> new FinancialContract(
                UUID.randomUUID(), tenantId, "Invalid",
                FinancialContract.Modality.SAC, Money.brl("1000.00"),
                BigDecimal.ZERO, 12, -1, LocalDate.now()
        ));

        assertThrows(IllegalArgumentException.class, () -> new FinancialContract(
                UUID.randomUUID(), tenantId, "Invalid",
                FinancialContract.Modality.SAC, Money.brl("1000.00"),
                BigDecimal.ZERO, 12, 13, LocalDate.now()
        ));
    }

    @Test
    void shouldValidateNullFields() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new FinancialContract(
                null, tenantId, "Name", FinancialContract.Modality.SAC, Money.zeroBrl(), BigDecimal.ZERO, 10, 10, LocalDate.now()
        ));
        assertThrows(NullPointerException.class, () -> new FinancialContract(
                id, null, "Name", FinancialContract.Modality.SAC, Money.zeroBrl(), BigDecimal.ZERO, 10, 10, LocalDate.now()
        ));
        assertThrows(NullPointerException.class, () -> new FinancialContract(
                id, tenantId, null, FinancialContract.Modality.SAC, Money.zeroBrl(), BigDecimal.ZERO, 10, 10, LocalDate.now()
        ));
        assertThrows(NullPointerException.class, () -> new FinancialContract(
                id, tenantId, "Name", null, Money.zeroBrl(), BigDecimal.ZERO, 10, 10, LocalDate.now()
        ));
        assertThrows(NullPointerException.class, () -> new FinancialContract(
                id, tenantId, "Name", FinancialContract.Modality.SAC, null, BigDecimal.ZERO, 10, 10, LocalDate.now()
        ));
        assertThrows(NullPointerException.class, () -> new FinancialContract(
                id, tenantId, "Name", FinancialContract.Modality.SAC, Money.zeroBrl(), null, 10, 10, LocalDate.now()
        ));
        assertThrows(NullPointerException.class, () -> new FinancialContract(
                id, tenantId, "Name", FinancialContract.Modality.SAC, Money.zeroBrl(), BigDecimal.ZERO, 10, 10, null
        ));
    }
}
