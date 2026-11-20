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
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.enums.StageStatus;
import com.policyflow.policyflow.exception.NoEligibleApproverException;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional // everything is rolled back, so your real data and policies are untouched
class RequestRoutingTest {

    @Autowired private RequestService requestService;
    @Autowired private RequestRepository requestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private EntityManager entityManager;

    private Department department;
    private User employee;

    @BeforeEach
    void setUp() {
        // Only the policies created inside each test apply
        List<ApprovalPolicy> existing = policyRepository.findAll();
        existing.forEach(policy -> policy.setActive(false)); // rolled back after the test
        policyRepository.saveAllAndFlush(existing);

        department = departmentRepository.save(new Department("Routing Test", "RTGTST"));
        employee = saveUser("rtg-employee", Role.EMPLOYEE, department);
    }

    // ---- helpers ----

    private User saveUser(String emailPrefix, Role role, Department dept) {
        return userRepository.save(
                new User(role.getLabel() + " " + emailPrefix, emailPrefix + "@policyflow.local", role, dept));
    }

    private void addAllApprovers(Department dept, String prefix) {
        saveUser(prefix + "-manager", Role.MANAGER, dept);
        saveUser(prefix + "-finance", Role.FINANCE_OFFICER, dept);
        saveUser(prefix + "-head", Role.DEPARTMENT_HEAD, dept);
    }

    private ApprovalPolicy savePolicy(String name, String min, String max, Role... roles) {
        ApprovalPolicy policy = new ApprovalPolicy(name, new BigDecimal(min),
                max == null ? null : new BigDecimal(max), null, null);
        for (Role role : roles) {
            policy.addApprover(role);
        }
        return policyRepository.saveAndFlush(policy);
    }

    private RequestForm form(String amount, RequestCategory category) {
        RequestForm form = new RequestForm();
        form.setTitle("Routing test request");
        form.setCategory(category);
        form.setAmount(new BigDecimal(amount));
        form.setDescription("Created by an automated test.");
        return form;
    }

    private List<Role> rolesOf(Request request) {
        return request.getStages().stream().map(RequestStage::getApproverRole).toList();
    }

    // ---- routing and the stage snapshot ----

    @Test
    void multiStageRequestIsStoredWithOrderedStagesAndOnlyTheFirstIsPending() {
        addAllApprovers(department, "rtg-a");
        savePolicy("RT all three", "0", null, Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD);

        Request saved = requestService.createAndSubmit(form("85000.00", RequestCategory.HARDWARE), employee);
        Long requestId = saved.getId();
        Long employeeId = employee.getId();
        entityManager.flush();
        entityManager.clear(); // the next read must come from MySQL, not from memory

        Request reloaded = requestRepository.findByIdAndEmployeeId(requestId, employeeId).orElseThrow();
        List<RequestStage> stages = reloaded.getStages();

        assertEquals(RequestStatus.PENDING_APPROVAL, reloaded.getStatus());
        assertEquals(3, stages.size());
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD), rolesOf(reloaded));
        assertEquals(List.of(1, 2, 3), stages.stream().map(RequestStage::getStepNumber).toList());
        assertEquals(List.of(StageStatus.PENDING, StageStatus.WAITING, StageStatus.WAITING),
                stages.stream().map(RequestStage::getStatus).toList());
        assertEquals(Role.MANAGER, reloaded.getCurrentStage().getApproverRole());
    }

    @Test
    void singleStageRequestHasOnePendingStage() {
        addAllApprovers(department, "rtg-b");
        savePolicy("RT small", "0", "10000", Role.MANAGER);
        savePolicy("RT large", "10000", null, Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD);

        Request saved = requestService.createAndSubmit(form("5000", RequestCategory.HARDWARE), employee);

        assertEquals(List.of(Role.MANAGER), rolesOf(saved));
        assertEquals(StageStatus.PENDING, saved.getStages().get(0).getStatus());
    }

    @Test
    void noMatchingPolicyRoutesToTheDepartmentHeadAndRecordsWhy() {
        addAllApprovers(department, "rtg-c"); // and no policies at all

        Request saved = requestService.createAndSubmit(form("5000", RequestCategory.HARDWARE), employee);

        assertEquals(List.of(Role.DEPARTMENT_HEAD), rolesOf(saved));
        assertEquals(RequestStatus.PENDING_APPROVAL, saved.getStatus());
        assertTrue(saved.getRoutingSummary().contains("No active policy matched"));
    }

    @Test
    void routingSummaryNamesTheMatchedPolicies() {
        addAllApprovers(department, "rtg-d");
        ApprovalPolicy policy = savePolicy("RT named policy", "0", null, Role.MANAGER);

        Request saved = requestService.createAndSubmit(form("5000", RequestCategory.OTHER), employee);

        assertNotNull(saved.getRoutingSummary());
        assertTrue(saved.getRoutingSummary().contains("RT named policy"));
        assertTrue(saved.getRoutingSummary().contains("policy #" + policy.getId()));
    }

    @Test
    void laterPolicyChangesDoNotAlterAnExistingRequestsStages() {
        addAllApprovers(department, "rtg-e");
        ApprovalPolicy original = savePolicy("RT original", "0", null, Role.MANAGER, Role.FINANCE_OFFICER);

        Request saved = requestService.createAndSubmit(form("20000", RequestCategory.OTHER), employee);
        Long requestId = saved.getId();
        Long employeeId = employee.getId();

        // The company changes its rules afterwards
        original.setActive(false);
        savePolicy("RT replacement", "0", null, Role.DEPARTMENT_HEAD);
        entityManager.flush();
        entityManager.clear();

        Request reloaded = requestRepository.findByIdAndEmployeeId(requestId, employeeId).orElseThrow();
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), rolesOf(reloaded));
    }

    // ---- who counts as an eligible approver ----

    @Test
    void aManagerRequestMovesUpToTheFinanceOfficer() {
        Department solo = departmentRepository.save(new Department("Solo Dept", "RTGSOLO"));
        User soloManager = saveUser("rtg-solo-manager", Role.MANAGER, solo);
        saveUser("rtg-solo-finance", Role.FINANCE_OFFICER, solo);
        savePolicy("RT manager", "0", null, Role.MANAGER);

        // The requester is the only manager in the department, but that no longer blocks the request
        Request saved = requestService.createAndSubmit(form("5000", RequestCategory.OTHER), soloManager);
        assertEquals(List.of(Role.FINANCE_OFFICER), rolesOf(saved));
    }

    @Test
    void requesterIsNeverTheirOwnApprover() {
        Department solo = departmentRepository.save(new Department("Solo Dept", "RTGSOLO"));
        User soloHead = saveUser("rtg-solo-head", Role.DEPARTMENT_HEAD, solo);
        savePolicy("RT head", "0", null, Role.DEPARTMENT_HEAD);

        // The only Department Head in the department is the requester: nobody above, nobody else to decide
        assertThrows(NoEligibleApproverException.class,
                () -> requestService.createAndSubmit(form("5000", RequestCategory.OTHER), soloHead));

        // Once a second Department Head exists, the same request can be submitted
        saveUser("rtg-second-head", Role.DEPARTMENT_HEAD, solo);
        Request saved = requestService.createAndSubmit(form("5000", RequestCategory.OTHER), soloHead);
        assertEquals(List.of(Role.DEPARTMENT_HEAD), rolesOf(saved));
    }

    @Test
    void aManagerFromAnotherDepartmentDoesNotCount() {
        Department other = departmentRepository.save(new Department("Other Dept", "RTGOTHER"));
        saveUser("rtg-other-manager", Role.MANAGER, other);
        savePolicy("RT manager", "0", null, Role.MANAGER);

        assertThrows(NoEligibleApproverException.class,
                () -> requestService.createAndSubmit(form("5000", RequestCategory.OTHER), employee));
    }

    @Test
    void anInactiveApproverDoesNotCount() {
        User manager = saveUser("rtg-inactive-manager", Role.MANAGER, department);
        manager.setActive(false);
        userRepository.saveAndFlush(manager);
        savePolicy("RT manager", "0", null, Role.MANAGER);

        assertThrows(NoEligibleApproverException.class,
                () -> requestService.createAndSubmit(form("5000", RequestCategory.OTHER), employee));
    }

    @Test
    void aFinanceOfficerFromAnyDepartmentCanApprove() {
        Department financeDepartment = departmentRepository.save(new Department("Finance Dept", "RTGFIN"));
        saveUser("rtg-finance-officer", Role.FINANCE_OFFICER, financeDepartment);
        savePolicy("RT finance", "0", null, Role.FINANCE_OFFICER);

        Request saved = requestService.createAndSubmit(form("5000", RequestCategory.OTHER), employee);

        assertEquals(List.of(Role.FINANCE_OFFICER), rolesOf(saved));
    }
}