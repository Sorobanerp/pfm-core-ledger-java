package br.com.pfm.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Money Domain Value Object Comprehensive Unit Tests")
class MoneyDomainUnitTest {

    @Test
    void shouldCreateMoneyInstancesCorrectly() {
        Money m1 = Money.of(new BigDecimal("100.50"), "BRL");
        assertEquals(new BigDecimal("100.5000"), m1.amount());
        assertEquals("BRL", m1.currency());

        Money m2 = Money.of("250.75", "USD");
        assertEquals(new BigDecimal("250.7500"), m2.amount());
        assertEquals("USD", m2.currency());

        Money m3 = Money.brl("10.00");
        assertEquals("BRL", m3.currency());
        assertEquals("10.0000", m3.toCanonicalString());

        Money zeroUsd = Money.zero("USD");
        assertTrue(zeroUsd.isZero());
        assertFalse(zeroUsd.isPositive());
        assertFalse(zeroUsd.isNegative());
    }

    @Test
    void shouldPerformAddAndSubtract() {
        Money a = Money.brl("100.0000");
        Money b = Money.brl("50.2500");

        Money sum = a.add(b);
        assertEquals(Money.brl("150.2500"), sum);

        Money diff = a.subtract(b);
        assertEquals(Money.brl("49.7500"), diff);
    }

    @Test
    void shouldPerformMultiply() {
        Money a = Money.brl("25.1000");
        Money mInt = a.multiply(4);
        assertEquals(Money.brl("100.4000"), mInt);

        Money mDec = a.multiply(new BigDecimal("2.5"));
        assertEquals(Money.brl("62.7500"), mDec);
    }

    @Test
    void shouldValidatePredicates() {
        assertTrue(Money.brl("1.00").isPositive());
        assertFalse(Money.brl("-1.00").isPositive());
        assertFalse(Money.zeroBrl().isPositive());

        assertTrue(Money.brl("-0.01").isNegative());
        assertFalse(Money.brl("0.01").isNegative());
        assertFalse(Money.zeroBrl().isNegative());

        assertTrue(Money.zeroBrl().isZero());
        assertFalse(Money.brl("0.0001").isZero());
    }

    @Test
    void shouldHandleInstallmentSplits() {
        Money single = Money.brl("100.00");
        List<Money> singleSplit = single.splitIntoInstallments(1);
        assertEquals(1, singleSplit.size());
        assertEquals(single, singleSplit.get(0));

        // 100 divided into 3: 33.34 in 1st, 33.33 in 2nd and 3rd
        Money total100 = Money.brl("100.0000");
        List<Money> threeSplits = total100.splitIntoInstallments(3);
        assertEquals(3, threeSplits.size());
        assertEquals(Money.brl("33.3400"), threeSplits.get(0));
        assertEquals(Money.brl("33.3300"), threeSplits.get(1));
        assertEquals(Money.brl("33.3300"), threeSplits.get(2));

        Money sum = threeSplits.get(0).add(threeSplits.get(1)).add(threeSplits.get(2));
        assertEquals(total100, sum);

        assertThrows(IllegalArgumentException.class, () -> total100.splitIntoInstallments(0));
        assertThrows(IllegalArgumentException.class, () -> total100.splitIntoInstallments(-5));
    }

    @Test
    void shouldValidateCurrencyMismatchAndBadInputs() {
        Money brl = Money.brl("10.00");
        Money usd = Money.of("10.00", "USD");

        assertThrows(IllegalArgumentException.class, () -> brl.add(usd));
        assertThrows(IllegalArgumentException.class, () -> brl.subtract(usd));
        assertThrows(IllegalArgumentException.class, () -> brl.compareTo(usd));

        assertThrows(NullPointerException.class, () -> Money.of((BigDecimal) null, "BRL"));
        assertThrows(NullPointerException.class, () -> Money.of(BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> Money.of(BigDecimal.ONE, ""));
        assertThrows(IllegalArgumentException.class, () -> Money.of(BigDecimal.ONE, "BRLX"));
        assertThrows(NullPointerException.class, () -> Money.brl((String) null));
        assertThrows(NullPointerException.class, () -> brl.multiply((BigDecimal) null));
    }

    @Test
    void shouldVerifyComparableAndObjectContract() {
        Money m1 = Money.brl("50.00");
        Money m2 = Money.brl("100.00");
        Money m3 = Money.brl("50.00");

        assertTrue(m1.compareTo(m2) < 0);
        assertTrue(m2.compareTo(m1) > 0);
        assertEquals(0, m1.compareTo(m3));

        assertEquals(m1, m3);
        assertNotEquals(m1, m2);
        assertNotEquals(m1, "not-money");
        assertNotEquals(m1, null);
        assertEquals(m1.hashCode(), m3.hashCode());

        assertEquals("BRL 50.0000", m1.toString());
    }
}
