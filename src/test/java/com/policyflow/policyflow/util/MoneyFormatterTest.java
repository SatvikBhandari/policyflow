package com.policyflow.policyflow.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyFormatterTest {

    private static final String R = "\u20B9";

    private final MoneyFormatter money = new MoneyFormatter();

    @Test
    void groupsDigitsTheIndianWay() {
        assertEquals(R + "85,000.00", money.format(new BigDecimal("85000"), 2));
        assertEquals(R + "1,25,000.00", money.format(new BigDecimal("125000"), 2));
        assertEquals(R + "12,50,000", money.format(new BigDecimal("1250000"), 0));
        assertEquals(R + "1,00,00,000", money.format(new BigDecimal("10000000"), 0));
    }

    @Test
    void smallAmountsNeedNoSeparatorUntilOneThousand() {
        assertEquals(R + "999.00", money.format(new BigDecimal("999"), 2));
        assertEquals(R + "1,000.00", money.format(new BigDecimal("1000"), 2));
    }

    @Test
    void keepsRequestedDecimalPlaces() {
        assertEquals(R + "1,250.50", money.format(new BigDecimal("1250.5"), 2));
        assertEquals(R + "1,251", money.format(new BigDecimal("1250.5"), 0));
    }

    @Test
    void roundsHalfUp() {
        assertEquals(R + "10.56", money.format(new BigDecimal("10.555"), 2));
    }

    @Test
    void formatsZero() {
        assertEquals(R + "0.00", money.format(BigDecimal.ZERO, 2));
    }

    @Test
    void nullAmountShowsADash() {
        assertEquals("\u2014", money.format(null, 2));
    }

    @Test
    void negativeAmountsKeepTheirSign() {
        assertEquals("-" + R + "1,25,000", money.format(new BigDecimal("-125000"), 0));
    }
}