package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;

class PolicyEvaluationServiceTest {

    private final PolicyEvaluationService service = new PolicyEvaluationService(null); // pure logic needs no repository

    // ---- helpers ----

    private static ApprovalPolicy policy(long id, String name, String min, String max,
                                         RequestCategory category, Department department, Role... roles) {
        ApprovalPolicy policy = new ApprovalPolicy(name, new BigDecimal(min),
                max == null ? null : new BigDecimal(max), category, department);
        ReflectionTestUtils.setField(policy, "id", id);
        for (Role role : roles) {
            policy.addApprover(role);
        }
        return policy;
    }

    private static Department department(long id) {
        Department department = new Department("Dept " + id, "D" + id);
        ReflectionTestUtils.setField(department, "id", id);
        return department;
    }

    /** The same four policies the development seeder creates. */
    private static List<ApprovalPolicy> samplePolicies() {
        return List.of(
                policy(1, "Low value", "0", "10000", null, null, Role.MANAGER),
                policy(2, "Mid value", "10000", "50000", null, null, Role.MANAGER, Role.FINANCE_OFFICER),
                policy(3, "High value", "50000", null, null, null,
                        Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD),
                policy(4, "Software", "0", null, RequestCategory.SOFTWARE, null, Role.FINANCE_OFFICER));
    }

    private PolicyEvaluation evaluateSample(String amount, RequestCategory category) {
        return service.evaluate(new BigDecimal(amount), category, null, samplePolicies());
    }

    // ---- amount bands and their boundaries ----

    @Test
    void belowTenThousandNeedsManagerOnly() {
        PolicyEvaluation result = evaluateSample("9999.99", RequestCategory.HARDWARE);

        assertEquals(List.of(Role.MANAGER), result.getApproverRoles());
        assertFalse(result.isFallbackUsed());
    }

    @Test
    void exactlyTenThousandMovesIntoTheMiddleBand() {
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER),
                evaluateSample("10000.00", RequestCategory.HARDWARE).getApproverRoles());
    }

    @Test
    void justBelowFiftyThousandStillNeedsOnlyManagerAndFinance() {
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER),
                evaluateSample("49999.99", RequestCategory.HARDWARE).getApproverRoles());
    }

    @Test
    void exactlyFiftyThousandMovesIntoTheTopBand() {
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD),
                evaluateSample("50000.00", RequestCategory.HARDWARE).getApproverRoles());
    }

    @Test
    void eightyFiveThousandNeedsAllThreeApprovers() {
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD),
                evaluateSample("85000.00", RequestCategory.HARDWARE).getApproverRoles());
    }

    @Test
    void smallestAllowedAmountMatchesTheLowBand() {
        assertEquals(List.of(Role.MANAGER),
                evaluateSample("0.01", RequestCategory.OTHER).getApproverRoles());
    }

    @Test
    void boundaryComparisonIgnoresDecimalScale() {
        // 10000, 10000.0 and 10000.00 are the same amount and must land in the same band
        for (String amount : new String[] {"10000", "10000.0", "10000.00"}) {
            assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER),
                    evaluateSample(amount, RequestCategory.TRAVEL).getApproverRoles(), amount);
        }
    }

    // ---- combining policies ----

    @Test
    void softwareCategoryAddsFinanceToTheAmountBand() {
        PolicyEvaluation result = evaluateSample("5000.00", RequestCategory.SOFTWARE);

        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), result.getApproverRoles());
        assertEquals(2, result.getMatchedPolicies().size());
    }

    @Test
    void overlappingPoliciesAreUnionedWithoutDuplicates() {
        // "High value" and "Software" both require finance; it must appear only once
        PolicyEvaluation result = evaluateSample("85000.00", RequestCategory.SOFTWARE);

        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD),
                result.getApproverRoles());
        assertEquals(2, result.getMatchedPolicies().size());
    }

    @Test
    void resultOrderDoesNotDependOnPolicyOrder() {
        List<ApprovalPolicy> reversed = new ArrayList<>(samplePolicies());
        Collections.reverse(reversed);

        PolicyEvaluation result = service.evaluate(
                new BigDecimal("85000"), RequestCategory.SOFTWARE, null, reversed);

        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD),
                result.getApproverRoles());
    }

    @Test
    void matchedPoliciesAreReportedWithIdAndName() {
        PolicyEvaluation result = evaluateSample("20000", RequestCategory.HARDWARE);

        assertEquals(1, result.getMatchedPolicies().size());
        assertEquals(2L, result.getMatchedPolicies().get(0).getId());
        assertEquals("Mid value", result.getMatchedPolicies().get(0).getName());
    }

    // ---- safe fallback and bad configuration ----

    @Test
    void noPoliciesFallsBackToDepartmentHead() {
        PolicyEvaluation result = service.evaluate(
                new BigDecimal("5000"), RequestCategory.HARDWARE, null, List.of());

        assertTrue(result.isFallbackUsed());
        assertEquals(List.of(Role.DEPARTMENT_HEAD), result.getApproverRoles());
        assertTrue(result.getReason().contains("No active policy matched"));
    }

    @Test
    void amountOutsideEveryRangeFallsBack() {
        List<ApprovalPolicy> policies = List.of(
                policy(1, "Small only", "0", "100", null, null, Role.MANAGER));

        PolicyEvaluation result = service.evaluate(
                new BigDecimal("500"), RequestCategory.OTHER, null, policies);

        assertTrue(result.isFallbackUsed());
        assertEquals(List.of(Role.DEPARTMENT_HEAD), result.getApproverRoles());
    }

    @Test
    void inactivePolicyIsIgnored() {
        ApprovalPolicy inactive = policy(1, "Switched off", "0", null, null, null, Role.MANAGER);
        inactive.setActive(false);

        PolicyEvaluation result = service.evaluate(
                new BigDecimal("500"), RequestCategory.OTHER, null, List.of(inactive));

        assertTrue(result.isFallbackUsed());
        assertEquals(List.of(Role.DEPARTMENT_HEAD), result.getApproverRoles());
    }

    @Test
    void matchingPolicyWithoutApproversFallsBack() {
        ApprovalPolicy empty = policy(1, "No approvers", "0", null, null, null); // no roles added

        PolicyEvaluation result = service.evaluate(
                new BigDecimal("500"), RequestCategory.OTHER, null, List.of(empty));

        assertTrue(result.isFallbackUsed());
        assertEquals(List.of(Role.DEPARTMENT_HEAD), result.getApproverRoles());
        assertEquals(1, result.getMatchedPolicies().size());
        assertTrue(result.getReason().contains("define no approvers"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonApproverRoleInCorruptedDataIsIgnored() {
        ApprovalPolicy corrupted = policy(1, "Corrupted row", "0", null, null, null, Role.MANAGER);
        // Simulate bad data in the table, which the entity itself would never allow
        ((Set<Role>) ReflectionTestUtils.getField(corrupted, "approverRoles")).add(Role.EMPLOYEE);

        PolicyEvaluation result = service.evaluate(
                new BigDecimal("500"), RequestCategory.OTHER, null, List.of(corrupted));

        assertEquals(List.of(Role.MANAGER), result.getApproverRoles());
    }

    // ---- other conditions ----

    @Test
    void categoryPolicyIgnoresOtherCategories() {
        List<ApprovalPolicy> policies = List.of(
                policy(1, "Travel only", "0", null, RequestCategory.TRAVEL, null, Role.FINANCE_OFFICER));

        assertEquals(List.of(Role.FINANCE_OFFICER),
                service.evaluate(new BigDecimal("100"), RequestCategory.TRAVEL, null, policies).getApproverRoles());
        assertTrue(service.evaluate(new BigDecimal("100"), RequestCategory.HARDWARE, null, policies)
                .isFallbackUsed());
    }

    @Test
    void departmentPolicyMatchesOnlyItsDepartment() {
        Department engineering = department(1);
        Department finance = department(2);
        List<ApprovalPolicy> policies = List.of(
                policy(1, "Engineering extra", "0", null, null, engineering, Role.FINANCE_OFFICER));
        BigDecimal amount = new BigDecimal("100");

        assertEquals(List.of(Role.FINANCE_OFFICER),
                service.evaluate(amount, RequestCategory.OTHER, engineering, policies).getApproverRoles());
        assertTrue(service.evaluate(amount, RequestCategory.OTHER, finance, policies).isFallbackUsed());
        assertTrue(service.evaluate(amount, RequestCategory.OTHER, null, policies).isFallbackUsed());
    }

    // ---- input checks and immutability ----

    @Test
    void invalidInputIsRejected() {
        List<ApprovalPolicy> policies = samplePolicies();

        assertThrows(IllegalArgumentException.class,
                () -> service.evaluate(null, RequestCategory.OTHER, null, policies));
        assertThrows(IllegalArgumentException.class,
                () -> service.evaluate(BigDecimal.ZERO, RequestCategory.OTHER, null, policies));
        assertThrows(IllegalArgumentException.class,
                () -> service.evaluate(new BigDecimal("-5"), RequestCategory.OTHER, null, policies));
        assertThrows(IllegalArgumentException.class,
                () -> service.evaluate(new BigDecimal("100"), null, null, policies));
    }

    @Test
    void resultListsCannotBeModified() {
        PolicyEvaluation result = evaluateSample("85000", RequestCategory.HARDWARE);

        assertThrows(UnsupportedOperationException.class, () -> result.getApproverRoles().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getMatchedPolicies().clear());
    }
}