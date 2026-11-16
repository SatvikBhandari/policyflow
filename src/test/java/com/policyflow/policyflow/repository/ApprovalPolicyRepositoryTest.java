package com.policyflow.policyflow.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional // everything is rolled back, so nothing stays in MySQL
class ApprovalPolicyRepositoryTest {

    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void savedPolicyReloadsWithConditionsAndApprovers() {
        Department department = departmentRepository.save(new Department("Policy Test", "POLTST"));
        ApprovalPolicy policy = new ApprovalPolicy("Repository test policy",
                new BigDecimal("10000.00"), new BigDecimal("50000.00"),
                RequestCategory.TRAVEL, department);
        policy.addApprover(Role.MANAGER);
        policy.addApprover(Role.FINANCE_OFFICER);

        Long id = policyRepository.saveAndFlush(policy).getId();
        entityManager.clear(); // forget the cached object so the next read hits MySQL

        ApprovalPolicy reloaded = policyRepository.findById(id).orElseThrow();
        assertEquals(0, new BigDecimal("10000.00").compareTo(reloaded.getMinAmount()));
        assertEquals(0, new BigDecimal("50000.00").compareTo(reloaded.getMaxAmount()));
        assertEquals(RequestCategory.TRAVEL, reloaded.getCategory());
        assertEquals(department.getId(), reloaded.getDepartment().getId());
        assertEquals(EnumSet.of(Role.MANAGER, Role.FINANCE_OFFICER), reloaded.getApproverRoles());
        assertTrue(reloaded.isActive());
        assertNotNull(reloaded.getVersion());
        assertNotNull(reloaded.getCreatedAt());
    }

    @Test
    void duplicatePolicyNamesAreRejectedByTheDatabase() {
        policyRepository.saveAndFlush(new ApprovalPolicy("Duplicate name test", BigDecimal.ZERO, null, null, null));

        assertThrows(DataIntegrityViolationException.class, () ->
                policyRepository.saveAndFlush(
                        new ApprovalPolicy("Duplicate name test", BigDecimal.ZERO, null, null, null)));
    }

    @Test
    void inactivePoliciesAreNotReturnedByTheActiveQuery() {
        ApprovalPolicy active = policyRepository.save(
                new ApprovalPolicy("Active test policy", BigDecimal.ZERO, null, null, null));
        ApprovalPolicy inactive = new ApprovalPolicy("Inactive test policy", BigDecimal.ZERO, null, null, null);
        inactive.setActive(false);
        inactive = policyRepository.save(inactive);

        Long activeId = active.getId();
        Long inactiveId = inactive.getId();
        var found = policyRepository.findByActiveTrue();

        assertTrue(found.stream().anyMatch(p -> p.getId().equals(activeId)));
        assertFalse(found.stream().anyMatch(p -> p.getId().equals(inactiveId)));
    }
}