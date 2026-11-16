package com.policyflow.policyflow.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProductionSafetyCheckTest {

    @Test
    void aSafeConfigurationHasNoProblems() {
        assertTrue(new ProductionSafetyCheck(false, true, "validate").problems().isEmpty());
        assertTrue(new ProductionSafetyCheck(false, true, "none").problems().isEmpty());
        assertTrue(new ProductionSafetyCheck(false, true, null).problems().isEmpty());
    }

    @Test
    void demoPasswordSeedingIsRefused() {
        assertEquals(1, new ProductionSafetyCheck(true, true, "validate").problems().size());
    }

    @Test
    void anInsecureSessionCookieIsRefused() {
        assertEquals(1, new ProductionSafetyCheck(false, false, "validate").problems().size());
    }

    @Test
    void hibernateGeneratingTheSchemaIsRefused() {
        for (String mode : new String[] {"update", "create", "create-drop", " UPDATE ", "Create-Drop"}) {
            assertEquals(1, new ProductionSafetyCheck(false, true, mode).problems().size(), mode);
        }
    }

    @Test
    void everyProblemIsReportedTogether() {
        assertEquals(3, new ProductionSafetyCheck(true, false, "update").problems().size());
    }

    @Test
    void startupFailsWhenUnsafeAndPassesWhenSafe() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ProductionSafetyCheck(true, false, "update").afterPropertiesSet());
        assertTrue(failure.getMessage().startsWith("Unsafe production configuration"));
        assertTrue(failure.getMessage().contains("cookie.secure"));

        assertDoesNotThrow(() -> new ProductionSafetyCheck(false, true, "validate").afterPropertiesSet());
    }
}