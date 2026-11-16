package com.policyflow.policyflow.security;

import java.nio.charset.StandardCharsets;

/** Rules every password must follow. BCrypt only uses the first 72 bytes, so longer passwords are refused. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** Returns a description of the problem, or null when the password is acceptable. */
    public static String check(String password) {
        if (password == null || password.isBlank()) {
            return "Password is required.";
        }
        if (password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters.";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "Password is too long (at most " + MAX_BYTES + " bytes).";
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return "Password must contain at least one letter and one digit.";
        }
        return null;
    }
}