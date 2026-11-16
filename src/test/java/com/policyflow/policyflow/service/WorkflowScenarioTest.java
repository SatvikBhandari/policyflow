package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.DashboardSummary;
import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.enums.StageStatus;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.WorkflowFixtures;

import jakarta.persistence.EntityManager;

/**
 * End-to-end workflow scenarios at service level, against the real MySQL database.
 * Everything is rolled back after each test, so your real data and policies are untouched.
 */
@SpringBootTest
@Transactional
class WorkflowScenarioTest {

    private record Scenario(String amount, RequestCategory category, List<Role> chain) {
    }

    @Autowired private RequestService requestService;
    @Autowired private ApprovalService approvalService;
    @Autowired private DashboardService dashboardService;
    @Autowired private RequestRepository requestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;

    private Department department;
    private User employee;
    private User manager;
    private User finance;
    private User head;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository); // only policies created by each test apply

        department = departmentRepository.save(new Department("Scenario Dept", "E2EDEPT"));
        Department financeDepartment = departmentRepository.save(new Department("Scenario Finance", "E2EFIN"));

        employee = WorkflowFixtures.user(userRepository, "e2e-employee", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "e2e-manager", Role.MANAGER, department);
        finance = WorkflowFixtures.user(userRepository, "e2e-finance", Role.FINANCE_OFFICER, financeDepartment);
        head = WorkflowFixtures.user(userRepository, "e2e-head", Role.DEPARTMENT_HEAD, department);
    }

    // ---- helpers ----

    /** The same four sample policies the development seeder creates. */
    private void createSamplePolicies() {
        policy("E2E low", "0", "10000", null, Role.MANAGER);
        policy("E2E mid", "10000", "50000", null, Role.MANAGER, Role.FINANCE_OFFICER);
        policy("E2E high", "50000", null, null, Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD);
        policy("E2E software", "0", null, RequestCategory.SOFTWARE, Role.FINANCE_OFFICER);
    }

    private ApprovalPolicy policy(String name, String min, String max, RequestCategory category, Role... roles) {
        ApprovalPolicy policy = new ApprovalPolicy(name, new BigDecimal(min),
                max == null ? null : new BigDecimal(max), category, null);
        for (Role role : roles) {
            policy.addApprover(role);
        }
        return policyRepository.saveAndFlush(policy);
    }

    private RequestForm form(String amount, RequestCategory category) {
        RequestForm form = new RequestForm();
        form.setTitle("Scenario request");
        form.setCategory(category);
        form.setAmount(new BigDecimal(amount));
        form.setDescription("Created by an automated scenario test.");
        return form;
    }

    private Long submit(String amount) {
        return requestService.createAndSubmit(form(amount, RequestCategory.OTHER), employee).getId();
    }

    private User approverFor(Role role) {
        return switch (role) {
            case MANAGER -> manager;
            case FINANCE_OFFICER -> finance;
            case DEPARTMENT_HEAD -> head;
            default -> throw new IllegalArgumentException("Not an approver role: " + role);
        };
    }

    /** Reads the request again from MySQL (not from memory). */
    private Request reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return requestRepository.findDetailedById(id).orElseThrow();
    }

    private List<Role> rolesOf(Request request) {
        return request.getStages().stream().map(RequestStage::getApproverRole).toList();
    }

    private List<StageStatus> stageStatuses(Request request) {
        return request.getStages().stream().map(RequestStage::getStatus).toList();
    }

    private List<String> auditActions(Long requestId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_REQUEST, requestId)
                .stream().map(AuditLog::getAction).toList();
    }

    // ---- scenarios ----

    @Test
    void everyPolicyBandAndCategoryRunsThroughToApproval() {
        createSamplePolicies();
        List<Scenario> scenarios = List.of(
                new Scenario("0.01", RequestCategory.OTHER, List.of(Role.MANAGER)),
                new Scenario("9999.99", RequestCategory.HARDWARE, List.of(Role.MANAGER)),
                new Scenario("10000.00", RequestCategory.HARDWARE, List.of(Role.MANAGER, Role.FINANCE_OFFICER)),
                new Scenario("49999.99", RequestCategory.TRAVEL, List.of(Role.MANAGER, Role.FINANCE_OFFICER)),
                new Scenario("50000.00", RequestCategory.OFFICE_SUPPLIES,
                        List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD)),
                new Scenario("85000.00", RequestCategory.HARDWARE,
                        List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD)),
                new Scenario("5000.00", RequestCategory.SOFTWARE, List.of(Role.MANAGER, Role.FINANCE_OFFICER)),
                new Scenario("85000.00", RequestCategory.SOFTWARE,
                        List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD)));

        for (Scenario scenario : scenarios) {
            String label = scenario.amount() + " " + scenario.category();

            Request submitted = requestService.createAndSubmit(form(scenario.amount(), scenario.category()), employee);
            Long id = submitted.getId();
            assertEquals(RequestStatus.PENDING_APPROVAL, submitted.getStatus(), label);
            assertEquals(scenario.chain(), rolesOf(submitted), label);

            for (Role role : scenario.chain()) {
                approvalService.approve(id, approverFor(role), "ok");
            }

            Request done = reload(id);
            assertEquals(RequestStatus.APPROVED, done.getStatus(), label);
            assertTrue(stageStatuses(done).stream().allMatch(status -> status == StageStatus.APPROVED), label);
            assertEquals(scenario.chain().size(), approvalService.historyFor(id).size(), label);

            List<String> expectedAudit = new ArrayList<>();
            expectedAudit.add(AuditService.REQUEST_SUBMITTED);
            for (int i = 0; i < scenario.chain().size(); i++) {
                expectedAudit.add(AuditService.STAGE_APPROVED);
            }
            expectedAudit.add(AuditService.REQUEST_APPROVED);
            assertEquals(expectedAudit, auditActions(id), label);
        }
    }

    @Test
    void rejectionAtAnyStageStopsTheWorkflow() {
        createSamplePolicies();
        List<Role> chain = List.of(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD);

        for (int rejectAt = 1; rejectAt <= chain.size(); rejectAt++) {
            Long id = requestService.createAndSubmit(form("85000.00", RequestCategory.HARDWARE), employee).getId();

            for (int step = 1; step < rejectAt; step++) {
                approvalService.approve(id, approverFor(chain.get(step - 1)), null);
            }
            approvalService.reject(id, approverFor(chain.get(rejectAt - 1)), "Rejected at step " + rejectAt);

            Request rejected = reload(id);
            assertEquals(RequestStatus.REJECTED, rejected.getStatus(), "reject at " + rejectAt);

            for (RequestStage stage : rejected.getStages()) {
                StageStatus expected;
                if (stage.getStepNumber() < rejectAt) {
                    expected = StageStatus.APPROVED;
                } else if (stage.getStepNumber() == rejectAt) {
                    expected = StageStatus.REJECTED;
                } else {
                    expected = StageStatus.CLOSED;
                }
                assertEquals(expected, stage.getStatus(), "step " + stage.getStepNumber() + ", reject at " + rejectAt);
            }

            // Nobody further down the chain can still act on it
            for (int later = rejectAt + 1; later <= chain.size(); later++) {
                Role laterRole = chain.get(later - 1);
                assertThrows(ResourceNotFoundException.class,
                        () -> approvalService.approve(id, approverFor(laterRole), null));
            }
        }
    }

    @Test
    void noPolicyAtAllRoutesToTheDepartmentHeadAndCanBeApproved() {
        // no policies exist: the safe fallback applies
        Request saved = requestService.createAndSubmit(form("5000.00", RequestCategory.HARDWARE), employee);
        Long id = saved.getId();

        assertEquals(List.of(Role.DEPARTMENT_HEAD), rolesOf(saved));
        assertTrue(saved.getRoutingSummary().contains("No active policy matched"));

        approvalService.approve(id, head, "Manual decision");

        assertEquals(RequestStatus.APPROVED, reload(id).getStatus());
    }

    @Test
    void policyChangesDoNotAffectRequestsAlreadyInFlight() {
        ApprovalPolicy original = policy("E2E original", "0", null, null, Role.MANAGER, Role.FINANCE_OFFICER);
        Long inFlightId = submit("20000.00");

        // The company changes its rules afterwards
        original.setActive(false);
        policyRepository.saveAndFlush(original);
        policy("E2E replacement", "0", null, null, Role.DEPARTMENT_HEAD);
        Long newerId = submit("20000.00");

        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), rolesOf(reload(inFlightId)));
        assertEquals(List.of(Role.DEPARTMENT_HEAD), rolesOf(reload(newerId)));

        // The in-flight request still completes under the rules it was submitted with
        approvalService.approve(inFlightId, manager, null);
        approvalService.approve(inFlightId, finance, null);
        assertEquals(RequestStatus.APPROVED, reload(inFlightId).getStatus());
    }

    @Test
    void aBackupApproverCanTakeOverWhenTheFirstOneIsDeactivated() {
        policy("E2E manager only", "0", null, null, Role.MANAGER);
        User backupManager = WorkflowFixtures.user(userRepository, "e2e-backup-manager", Role.MANAGER, department);
        Long id = submit("5000.00");

        manager.setActive(false);
        userRepository.saveAndFlush(manager);

        // Eligibility is re-checked at decision time
        assertThrows(ResourceNotFoundException.class, () -> approvalService.approve(id, manager, "too late"));
        approvalService.approve(id, backupManager, "Covering for a colleague");

        assertEquals(RequestStatus.APPROVED, reload(id).getStatus());
    }

    @Test
    void dashboardCountsEveryOutcomeAndLeavesCancelledOutOfTheTotal() {
        policy("E2E manager only", "0", null, null, Role.MANAGER);
        Long approvedId = submit("5000.00");
        Long rejectedId = submit("5000.00");
        submit("5000.00"); // stays pending
        Long cancelledId = submit("5000.00");

        approvalService.approve(approvedId, manager, null);
        approvalService.reject(rejectedId, manager, "Not justified");
        requestService.cancel(cancelledId, employee, "Changed plans");

        DashboardSummary summary = dashboardService.summaryFor(employee);

        assertEquals(3, summary.getTotal()); // the cancelled request is not counted
        assertEquals(1, summary.getApproved());
        assertEquals(1, summary.getRejected());
        assertEquals(1, summary.getPendingApproval());
        assertEquals(1, summary.getCancelled());
        assertEquals(0, new BigDecimal("15000").compareTo(summary.getTotalAmount()));
    }

    @Test
    void auditTrailMatchesEachOutcome() {
        policy("E2E manager only", "0", null, null, Role.MANAGER);
        Long approvedId = submit("5000.00");
        Long rejectedId = submit("5000.00");
        Long cancelledId = submit("5000.00");

        approvalService.approve(approvedId, manager, null);
        approvalService.reject(rejectedId, manager, "No");
        requestService.cancel(cancelledId, employee, null);

        assertEquals(List.of(AuditService.REQUEST_SUBMITTED, AuditService.STAGE_APPROVED,
                AuditService.REQUEST_APPROVED), auditActions(approvedId));
        assertEquals(List.of(AuditService.REQUEST_SUBMITTED, AuditService.REQUEST_REJECTED),
                auditActions(rejectedId));
        assertEquals(List.of(AuditService.REQUEST_SUBMITTED, AuditService.REQUEST_CANCELLED),
                auditActions(cancelledId));
    }
}