package com.policyflow.policyflow.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;

class ApprovalPolicyTest {

    private static ApprovalPolicy policy(String min, String max) {
        return new ApprovalPolicy("Test policy", new BigDecimal(min),
                max == null ? null : new BigDecimal(max), null, null);
    }

    @Test
    void validPolicyIsCreatedWithTrimmedNameAndOptionalConditions() {
        ApprovalPolicy policy = new ApprovalPolicy("  Mid value  ", new BigDecimal("10000"),
                new BigDecimal("50000"), RequestCategory.HARDWARE, null);

        assertEquals("Mid value", policy.getName());
        assertEquals(RequestCategory.HARDWARE, policy.getCategory());
        assertNull(policy.getDepartment());
        assertTrue(policy.isActive());
    }

    @Test
    void policyWithNoUpperLimitIsAllowed() {
        assertNull(policy("50000", null).getMaxAmount());
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalPolicy("   ", BigDecimal.ZERO, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalPolicy(null, BigDecimal.ZERO, null, null, null));
    }

    @Test
    void negativeOrMissingMinimumIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy("-1", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalPolicy("Test", null, null, null, null));
    }

    @Test
    void maximumMustBeGreaterThanMinimum() {
        assertThrows(IllegalArgumentException.class, () -> policy("10000", "10000")); // equal
        assertThrows(IllegalArgumentException.class, () -> policy("10000", "5000"));  // lower
    }

    @Test
    void onlyApproverRolesCanBeAdded() {
        ApprovalPolicy policy = policy("0", null);

        assertThrows(IllegalArgumentException.class, () -> policy.addApprover(Role.EMPLOYEE));
        assertThrows(IllegalArgumentException.class, () -> policy.addApprover(Role.ADMIN));
        assertThrows(IllegalArgumentException.class, () -> policy.addApprover(null));
    }

    @Test
    void approversComeBackInApprovalOrderWithoutDuplicates() {
        ApprovalPolicy policy = policy("0", null);
        policy.addApprover(Role.DEPARTMENT_HEAD);
        policy.addApprover(Role.MANAGER);
        policy.addApprover(Role.FINANCE_OFFICER);
        policy.addApprover(Role.MANAGER); // duplicate is ignored

        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD),
                policy.getApproverRolesInOrder());
    }

    @Test
    void approverSetCannotBeChangedFromOutside() {
        ApprovalPolicy policy = policy("0", null);

        assertThrows(UnsupportedOperationException.class,
                () -> policy.getApproverRoles().add(Role.MANAGER));
    }

    @Test
    void onlyThreeRolesAreApprovers() {
        assertTrue(Role.MANAGER.isApprover());
        assertTrue(Role.FINANCE_OFFICER.isApprover());
        assertTrue(Role.DEPARTMENT_HEAD.isApprover());
        assertFalse(Role.EMPLOYEE.isApprover());
        assertFalse(Role.ADMIN.isApprover());
    }
}