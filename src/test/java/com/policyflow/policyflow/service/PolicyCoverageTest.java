package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.policyflow.policyflow.dto.PolicyCoverage;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;

class PolicyCoverageTest {

    private static ApprovalPolicy general(String name, String min, String max) {
        ApprovalPolicy policy = new ApprovalPolicy(name, new BigDecimal(min),
                max == null ? null : new BigDecimal(max), null, null);
        policy.addApprover(Role.MANAGER);
        return policy;
    }

    private static void assertGap(PolicyCoverage.Gap gap, String from, String to) {
        assertEquals(0, new BigDecimal(from).compareTo(gap.getFrom()), "gap start");
        if (to == null) {
            assertTrue(gap.isOpenEnded(), "gap should be open-ended");
        } else {
            assertEquals(0, new BigDecimal(to).compareTo(gap.getTo()), "gap end");
        }
    }

    @Test
    void noPoliciesMeansNoGeneralPolicy() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(List.of());

        assertFalse(coverage.isGeneralPolicyPresent());
        assertFalse(coverage.isHealthy());
    }

    @Test
    void oneOpenEndedPolicyFromZeroCoversEverything() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(List.of(general("All", "0", null)));

        assertTrue(coverage.isHealthy());
    }

    @Test
    void contiguousBandsLeaveNoGap() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(List.of(
                general("Low", "0", "10000"),
                general("Mid", "10000", "50000"),
                general("High", "50000", null)));

        assertTrue(coverage.isHealthy());
    }

    @Test
    void aGapBetweenBandsIsReported() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(List.of(
                general("Low", "0", "10000"),
                general("High", "20000", null)));

        assertTrue(coverage.isGeneralPolicyPresent());
        assertEquals(1, coverage.getGaps().size());
        assertGap(coverage.getGaps().get(0), "10000", "20000");
    }

    @Test
    void aMissingUpperEndIsReportedAsAnOpenEndedGap() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(List.of(general("Low", "0", "10000")));

        assertEquals(1, coverage.getGaps().size());
        assertGap(coverage.getGaps().get(0), "10000", null);
    }

    @Test
    void aLateStartReportsAGapFromTheSmallestAmount() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(List.of(general("Late", "5000", null)));

        assertEquals(1, coverage.getGaps().size());
        assertGap(coverage.getGaps().get(0), "0.01", "5000");
    }

    @Test
    void startingAtTheSmallestAmountLeavesNoGap() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(List.of(general("Penny", "0.01", null)));

        assertTrue(coverage.isHealthy());
    }

    @Test
    void overlappingBandsAreNotAGap() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(List.of(
                general("First", "0", "20000"),
                general("Second", "10000", null)));

        assertTrue(coverage.isHealthy());
    }

    @Test
    void onlyActiveGeneralPoliciesWithApproversCount() {
        ApprovalPolicy inactive = general("Inactive", "0", null);
        inactive.setActive(false);

        ApprovalPolicy categoryOnly = new ApprovalPolicy("Category", BigDecimal.ZERO, null, RequestCategory.TRAVEL, null);
        categoryOnly.addApprover(Role.MANAGER);

        ApprovalPolicy departmentOnly = new ApprovalPolicy("Department", BigDecimal.ZERO, null, null,
                new Department("Eng", "ENG"));
        departmentOnly.addApprover(Role.MANAGER);

        ApprovalPolicy noApprovers = new ApprovalPolicy("No approvers", BigDecimal.ZERO, null, null, null);

        PolicyCoverage coverage = PolicyAdminService.coverageOf(
                List.of(inactive, categoryOnly, departmentOnly, noApprovers));

        assertFalse(coverage.isGeneralPolicyPresent());
    }

    @Test
    void inputOrderDoesNotMatter() {
        List<ApprovalPolicy> policies = new ArrayList<>(List.of(
                general("Low", "0", "10000"),
                general("Mid", "10000", "50000"),
                general("High", "50000", null)));
        Collections.reverse(policies);

        assertTrue(PolicyAdminService.coverageOf(policies).isHealthy());
    }
}