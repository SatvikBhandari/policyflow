package com.policyflow.policyflow.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/** Formats INR amounts with Indian digit grouping, e.g. 1250000 becomes ₹12,50,000. */
@Component("money")
public class MoneyFormatter {

    private static final String RUPEE = "\u20B9";

    public String format(BigDecimal amount, int decimals) {
        if (amount == null) {
            return "\u2014"; // em dash
        }
        BigDecimal scaled = amount.setScale(decimals, RoundingMode.HALF_UP);
        String plain = scaled.abs().toPlainString();

        String integerPart = plain;
        String fraction = "";
        int dot = plain.indexOf('.');
        if (dot >= 0) {
            integerPart = plain.substring(0, dot);
            fraction = plain.substring(dot);
        }

        String sign = scaled.signum() < 0 ? "-" : "";
        return sign + RUPEE + group(integerPart) + fraction;
    }

    /** Last three digits form one group; everything before them is grouped in twos. */
    private String group(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String lastThree = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);

        StringBuilder grouped = new StringBuilder();
        int firstGroupLength = rest.length() % 2; // 1 when the leading group has a single digit
        if (firstGroupLength == 1) {
            grouped.append(rest.charAt(0));
        }
        for (int i = firstGroupLength; i < rest.length(); i += 2) {
            if (grouped.length() > 0) {
                grouped.append(',');
            }
            grouped.append(rest, i, i + 2);
        }
        return grouped + "," + lastThree;
    }
}