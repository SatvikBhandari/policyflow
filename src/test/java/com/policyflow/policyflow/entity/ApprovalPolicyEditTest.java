package com.policyflow.policyflow.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;

class ApprovalPolicyEditTest {

    private static ApprovalPolicy policy() {
        ApprovalPolicy policy = new ApprovalPolicy("Original", new BigDecimal("0"), new BigDecimal("10000"), null, null);
        policy.addApprover(Role.MANAGER);
        return policy;
    }

    @Test
    void updateChangesEveryField() {
        ApprovalPolicy policy = policy();
        Department department = new Department("Eng", "ENG");

        policy.update("  Renamed  ", "New description", new BigDecimal("5000"), null,
                RequestCategory.SOFTWARE, department);

        assertEquals("Renamed", policy.getName());
        assertEquals("New description", policy.getDescription());
        assertEquals(0, new BigDecimal("5000").compareTo(policy.getMinAmount()));
        assertNull(policy.getMaxAmount());
        assertEquals(RequestCategory.SOFTWARE, policy.getCategory());
        assertEquals(department, policy.getDepartment());
    }

    @Test
    void updateRejectsInvalidValuesAndChangesNothing() {
        ApprovalPolicy policy = policy();

        assertThrows(IllegalArgumentException.class,
                () -> policy.update("   ", null, BigDecimal.ZERO, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> policy.update("Name", null, new BigDecimal("-1"), null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> policy.update("Name", null, new BigDecimal("500"), new BigDecimal("500"), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> policy.update("Name", null, new BigDecimal("500"), new BigDecimal("100"), null, null));

        assertEquals("Original", policy.getName());
        assertEquals(0, new BigDecimal("10000").compareTo(policy.getMaxAmount()));
    }

    @Test
    void replaceApproversSwapsTheRoles() {
        ApprovalPolicy policy = policy();

        policy.replaceApprovers(List.of(Role.DEPARTMENT_HEAD, Role.FINANCE_OFFICER));

        assertEquals(List.of(Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD), policy.getApproverRolesInOrder());
        assertTrue(!policy.getApproverRoles().contains(Role.MANAGER));
    }

    @Test
    void replaceApproversRejectsNonApproverRolesAndKeepsTheOldOnes() {
        ApprovalPolicy policy = policy();

        assertThrows(IllegalArgumentException.class,
                () -> policy.replaceApprovers(List.of(Role.FINANCE_OFFICER, Role.EMPLOYEE)));
        assertThrows(IllegalArgumentException.class, () -> policy.replaceApprovers(null));

        assertEquals(List.of(Role.MANAGER), policy.getApproverRolesInOrder());
    }

    @Test
    void replacingWithAnEmptyCollectionClearsTheApprovers() {
        ApprovalPolicy policy = policy();

        policy.replaceApprovers(List.of());

        assertTrue(policy.getApproverRoles().isEmpty());
    }
}