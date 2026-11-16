package com.policyflow.policyflow.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsAReasonablePassword() {
        assertNull(PasswordPolicy.check("Demo@12345"));
    }

    @Test
    void rejectsMissingOrBlankPasswords() {
        assertNotNull(PasswordPolicy.check(null));
        assertNotNull(PasswordPolicy.check(""));
        assertNotNull(PasswordPolicy.check("        "));
    }

    @Test
    void rejectsPasswordsShorterThanEightCharacters() {
        assertNotNull(PasswordPolicy.check("a1b2c3"));
    }

    @Test
    void rejectsPasswordsWithoutADigit() {
        assertNotNull(PasswordPolicy.check("onlyletters"));
    }

    @Test
    void rejectsPasswordsWithoutALetter() {
        assertNotNull(PasswordPolicy.check("12345678"));
    }

    @Test
    void rejectsPasswordsLongerThanBcryptCanUse() {
        assertNotNull(PasswordPolicy.check("a1".repeat(40))); // 80 bytes
    }

    @Test
    void acceptsAPasswordOfExactlySeventyTwoBytes() {
        assertNull(PasswordPolicy.check("a1".repeat(36))); // 72 bytes
    }
}