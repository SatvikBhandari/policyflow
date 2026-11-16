package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.NoEligibleApproverException;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.ApproverFixtures;

@SpringBootTest
@Transactional // every test is rolled back, so no test data stays in MySQL
class RequestServiceTest {

    @Autowired private RequestService requestService;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;

    private User employee;
    private User colleague;

    @BeforeEach
    void setUp() {
        // Use only the policy created here, so results do not depend on your real policies
        List<ApprovalPolicy> existing = policyRepository.findAll();
        existing.forEach(policy -> policy.setActive(false)); // rolled back after the test
        policyRepository.saveAllAndFlush(existing);

        ApprovalPolicy policy = new ApprovalPolicy("RS all requests", BigDecimal.ZERO, null, null, null);
        policy.addApprover(Role.MANAGER);
        policy.addApprover(Role.FINANCE_OFFICER);
        policyRepository.saveAndFlush(policy);

        Department department = departmentRepository.save(new Department("Service Test", "SVCTST"));
        ApproverFixtures.createApprovers(userRepository, department, "svc");
        employee = userRepository.save(
                new User("Service Employee", "svc-employee@policyflow.local", Role.EMPLOYEE, department));
        colleague = userRepository.save(
                new User("Service Colleague", "svc-colleague@policyflow.local", Role.EMPLOYEE, department));
    }

    private RequestForm sampleForm() {
        RequestForm form = new RequestForm();
        form.setTitle("  Laptop for new hire  ");
        form.setCategory(RequestCategory.HARDWARE);
        form.setAmount(new BigDecimal("85000.00"));
        form.setDescription("Needed for onboarding.");
        return form;
    }

    @Test
    void submittedRequestGetsReferenceStatusAndOwner() {
        Request saved = requestService.createAndSubmit(sampleForm(), employee);

        assertNotNull(saved.getId());
        assertTrue(saved.getReference().startsWith("PF-"));
        assertEquals(RequestStatus.PENDING_APPROVAL, saved.getStatus());
        assertNotNull(saved.getSubmittedAt());
        assertEquals("Laptop for new hire", saved.getTitle()); // trimmed
        assertEquals(employee.getId(), saved.getEmployee().getId());
    }

    @Test
    void anotherEmployeeCannotReadSomeoneElsesRequest() {
        Request saved = requestService.createAndSubmit(sampleForm(), employee);

        assertThrows(ResourceNotFoundException.class,
                () -> requestService.getFor(saved.getId(), colleague));
        assertEquals(saved.getId(), requestService.getFor(saved.getId(), employee).getId());
    }

    @Test
    void submittingTwiceIsRejected() {
        Request saved = requestService.createAndSubmit(sampleForm(), employee);

        assertThrows(IllegalStateException.class, saved::submit);
    }

    @Test
    void requestWithoutAnEligibleApproverIsRejectedAndNothingIsSaved() {
        Department emptyDepartment = departmentRepository.save(new Department("Empty Dept", "SVCEMPTY"));
        User lonely = userRepository.save(
                new User("Lonely Employee", "svc-lonely@policyflow.local", Role.EMPLOYEE, emptyDepartment));

        assertThrows(NoEligibleApproverException.class,
                () -> requestService.createAndSubmit(sampleForm(), lonely));
        assertTrue(requestService.findAllFor(lonely).isEmpty());
    }
}