package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.policyflow.policyflow.dto.PolicyCoverage;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.Role;

class PolicyPriorityEvaluationTest {

    private final PolicyEvaluationService service = new PolicyEvaluationService(null); // pure logic needs no repository

    private static ApprovalPolicy policy(String name, RequestPriority priority, Role... roles) {
        ApprovalPolicy policy = new ApprovalPolicy(name, BigDecimal.ZERO, null, null, null);
        policy.setPriority(priority);
        for (Role role : roles) {
            policy.addApprover(role);
        }
        return policy;
    }

    private List<Role> chain(RequestPriority priority, List<ApprovalPolicy> policies) {
        return service.evaluate(new BigDecimal("5000"), RequestCategory.OTHER, priority, null, policies)
                .getApproverRoles();
    }

    @Test
    void aPolicyLimitedToUrgentOnlyAppliesToUrgentRequests() {
        List<ApprovalPolicy> policies = List.of(
                policy("Base", null, Role.MANAGER),
                policy("Urgent extra", RequestPriority.URGENT, Role.FINANCE_OFFICER));

        assertEquals(List.of(Role.MANAGER), chain(RequestPriority.NORMAL, policies));
        assertEquals(List.of(Role.MANAGER), chain(RequestPriority.LOW, policies));
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), chain(RequestPriority.URGENT, policies));
    }

    @Test
    void aPolicyWithoutAPriorityConditionAppliesToEveryPriority() {
        List<ApprovalPolicy> policies = List.of(policy("Base", null, Role.MANAGER));

        for (RequestPriority priority : RequestPriority.values()) {
            assertEquals(List.of(Role.MANAGER), chain(priority, policies), priority.name());
        }
    }

    @Test
    void aMissingPriorityIsTreatedAsNormal() {
        List<ApprovalPolicy> urgentOnly = List.of(policy("Urgent only", RequestPriority.URGENT, Role.FINANCE_OFFICER));
        List<ApprovalPolicy> normalOnly = List.of(policy("Normal only", RequestPriority.NORMAL, Role.MANAGER));

        assertEquals(List.of(Role.DEPARTMENT_HEAD), chain(null, urgentOnly)); // nothing matched, so the safe fallback
        assertEquals(List.of(Role.FINANCE_OFFICER), chain(RequestPriority.URGENT, urgentOnly));
        assertEquals(List.of(Role.MANAGER), chain(null, normalOnly));
    }

    @Test
    void theOlderMethodsTreatRequestsAsNormal() {
        List<ApprovalPolicy> policies = List.of(
                policy("Normal only", RequestPriority.NORMAL, Role.MANAGER),
                policy("Urgent only", RequestPriority.URGENT, Role.FINANCE_OFFICER));

        assertEquals(List.of(Role.MANAGER),
                service.evaluate(new BigDecimal("5000"), RequestCategory.OTHER, null, policies).getApproverRoles());
    }

    @Test
    void priorityCombinesWithAmountAndCategory() {
        ApprovalPolicy urgentBigSoftware = new ApprovalPolicy("Urgent big software", new BigDecimal("10000"), null,
                RequestCategory.SOFTWARE, null);
        urgentBigSoftware.setPriority(RequestPriority.URGENT);
        urgentBigSoftware.addApprover(Role.DEPARTMENT_HEAD);
        List<ApprovalPolicy> policies = List.of(policy("Base", null, Role.MANAGER), urgentBigSoftware);

        List<Role> all = List.of(Role.MANAGER, Role.DEPARTMENT_HEAD);
        assertEquals(all, service.evaluate(new BigDecimal("20000"), RequestCategory.SOFTWARE,
                RequestPriority.URGENT, null, policies).getApproverRoles());
        assertEquals(List.of(Role.MANAGER), service.evaluate(new BigDecimal("20000"), RequestCategory.SOFTWARE,
                RequestPriority.NORMAL, null, policies).getApproverRoles());
        assertEquals(List.of(Role.MANAGER), service.evaluate(new BigDecimal("20000"), RequestCategory.HARDWARE,
                RequestPriority.URGENT, null, policies).getApproverRoles());
        assertEquals(List.of(Role.MANAGER), service.evaluate(new BigDecimal("5000"), RequestCategory.SOFTWARE,
                RequestPriority.URGENT, null, policies).getApproverRoles());
    }

    @Test
    void aPriorityLimitedPolicyIsNotAGeneralPolicyForCoverage() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(
                List.of(policy("Urgent only", RequestPriority.URGENT, Role.MANAGER)));

        assertFalse(coverage.isGeneralPolicyPresent());
    }

    @Test
    void aGeneralPolicyStillCoversWhenAPriorityLimitedOneIsAlsoPresent() {
        PolicyCoverage coverage = PolicyAdminService.coverageOf(List.of(
                policy("Base", null, Role.MANAGER),
                policy("Urgent only", RequestPriority.URGENT, Role.FINANCE_OFFICER)));

        assertTrue(coverage.isHealthy());
    }
}