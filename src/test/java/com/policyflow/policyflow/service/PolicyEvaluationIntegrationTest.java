package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;

@SpringBootTest
@Transactional // everything is rolled back, so your real policies are untouched
class PolicyEvaluationIntegrationTest {

    @Autowired private PolicyEvaluationService service;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private DepartmentRepository departmentRepository;

    @BeforeEach
    void isolateFromExistingPolicies() {
        List<ApprovalPolicy> existing = policyRepository.findAll();
        existing.forEach(policy -> policy.setActive(false)); // rolled back after the test
        policyRepository.saveAllAndFlush(existing);
    }

    private ApprovalPolicy save(String name, String min, String max, RequestCategory category,
                                Department department, Role... roles) {
        ApprovalPolicy policy = new ApprovalPolicy("IT " + name, new BigDecimal(min),
                max == null ? null : new BigDecimal(max), category, department);
        for (Role role : roles) {
            policy.addApprover(role);
        }
        return policyRepository.saveAndFlush(policy);
    }

    private ApprovalPolicy createSamplePolicies() {
        ApprovalPolicy low = save("low", "0", "10000", null, null, Role.MANAGER);
        save("mid", "10000", "50000", null, null, Role.MANAGER, Role.FINANCE_OFFICER);
        save("high", "50000", null, null, null, Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD);
        save("software", "0", null, RequestCategory.SOFTWARE, null, Role.FINANCE_OFFICER);
        return low;
    }

    @Test
    void engineUsesTheActivePoliciesStoredInTheDatabase() {
        createSamplePolicies();

        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD),
                service.evaluate(new BigDecimal("85000"), RequestCategory.HARDWARE, null).getApproverRoles());
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER),
                service.evaluate(new BigDecimal("5000"), RequestCategory.SOFTWARE, null).getApproverRoles());
        assertEquals(List.of(Role.MANAGER),
                service.evaluate(new BigDecimal("5000"), RequestCategory.HARDWARE, null).getApproverRoles());
    }

    @Test
    void deactivatedPolicyNoLongerApplies() {
        ApprovalPolicy low = createSamplePolicies();
        low.setActive(false);
        policyRepository.saveAndFlush(low);

        PolicyEvaluation result = service.evaluate(new BigDecimal("5000"), RequestCategory.HARDWARE, null);

        assertTrue(result.isFallbackUsed());
        assertEquals(List.of(Role.DEPARTMENT_HEAD), result.getApproverRoles());
    }

    @Test
    void noActivePoliciesFallsBackToDepartmentHead() {
        PolicyEvaluation result = service.evaluate(new BigDecimal("85000"), RequestCategory.TRAVEL, null);

        assertTrue(result.isFallbackUsed());
        assertEquals(List.of(Role.DEPARTMENT_HEAD), result.getApproverRoles());
    }

    @Test
    void departmentSpecificPolicyOnlyAppliesToThatDepartment() {
        Department engineering = departmentRepository.save(new Department("Eval Engineering", "EVALENG"));
        Department hr = departmentRepository.save(new Department("Eval HR", "EVALHR"));
        save("engineering only", "0", null, null, engineering, Role.FINANCE_OFFICER);

        PolicyEvaluation forEngineering =
                service.evaluate(new BigDecimal("100"), RequestCategory.OTHER, engineering);
        PolicyEvaluation forHr = service.evaluate(new BigDecimal("100"), RequestCategory.OTHER, hr);

        assertEquals(List.of(Role.FINANCE_OFFICER), forEngineering.getApproverRoles());
        assertFalse(forEngineering.isFallbackUsed());
        assertTrue(forHr.isFallbackUsed());
    }
}