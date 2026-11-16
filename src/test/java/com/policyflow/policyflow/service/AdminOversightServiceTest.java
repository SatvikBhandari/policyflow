package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.AdminViews.AuditEntry;
import com.policyflow.policyflow.dto.AdminViews.Overview;
import com.policyflow.policyflow.dto.AdminViews.StuckRequest;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.enums.StageStatus;
import com.policyflow.policyflow.exception.InvalidDecisionException;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.WorkflowFixtures;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class AdminOversightServiceTest {

    @Autowired private AdminOversightService service;
    @Autowired private RequestService requestService;
    @Autowired private ApprovalService approvalService;
    @Autowired private RequestRepository requestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;

    private Department department;
    private User admin;
    private User employee;
    private User manager;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository); // only policies created by each test apply
        WorkflowFixtures.policy(policyRepository, "OV manager only", Role.MANAGER);

        department = departmentRepository.save(new Department("Oversight Test", "OVTEST"));
        admin = WorkflowFixtures.user(userRepository, "ov-admin", Role.ADMIN, department);
        employee = WorkflowFixtures.user(userRepository, "ov-employee", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "ov-manager", Role.MANAGER, department);
    }

    // ---- helpers ----

    private Long submit() {
        return requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee).getId();
    }

    private void setActive(User user, boolean active) {
        user.setActive(active);
        userRepository.saveAndFlush(user);
    }

    private boolean isStuck(Long requestId) {
        return service.findStuck(admin).stream().anyMatch(s -> s.getRequestId().equals(requestId));
    }

    private Request reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return requestRepository.findDetailedById(id).orElseThrow();
    }

    private List<StageStatus> stageStatuses(Request request) {
        return request.getStages().stream().map(RequestStage::getStatus).toList();
    }

    private static long waiting(Overview overview, String roleLabel) {
        return overview.getPendingByRole().stream()
                .filter(r -> r.getLabel().equals(roleLabel)).findFirst().orElseThrow().getCount();
    }

    private static long statusCount(Overview overview, String statusLabel) {
        return overview.getStatusCounts().stream()
                .filter(s -> s.getLabel().equals(statusLabel)).findFirst().orElseThrow().getCount();
    }

    private static long activeUsers(Overview overview, String roleLabel) {
        return overview.getUsersByRole().stream()
                .filter(u -> u.getLabel().equals(roleLabel)).findFirst().orElseThrow().getActive();
    }

    // ---- stuck detection ----

    @Test
    void aRequestWithAnEligibleApproverIsNotStuck() {
        Long id = submit();

        assertFalse(isStuck(id));
    }

    @Test
    void aRequestWhoseOnlyApproverWasDeactivatedIsStuckUntilTheyAreBack() {
        Long id = submit();
        setActive(manager, false);

        StuckRequest stuck = service.findStuck(admin).stream()
                .filter(s -> s.getRequestId().equals(id)).findFirst().orElseThrow();
        assertEquals("Manager", stuck.getWaitingRoleLabel());
        assertEquals(1, stuck.getStepNumber());
        assertEquals(1, stuck.getTotalSteps());
        assertTrue(stuck.getExplanation().contains("Manager"));
        assertTrue(stuck.getExplanation().contains("Oversight Test"));

        setActive(manager, true);
        assertFalse(isStuck(id));
    }

    @Test
    void aRequestCanGetStuckAtALaterStageAndKeepsItsEarlierApproval() {
        WorkflowFixtures.policy(policyRepository, "OV manager and head", Role.MANAGER, Role.DEPARTMENT_HEAD);
        User head = WorkflowFixtures.user(userRepository, "ov-head", Role.DEPARTMENT_HEAD, department);
        Long id = submit();

        approvalService.approve(id, manager, "ok");
        assertFalse(isStuck(id));

        setActive(head, false);
        assertTrue(isStuck(id));
        assertEquals(2, service.findStuck(admin).stream()
                .filter(s -> s.getRequestId().equals(id)).findFirst().orElseThrow().getStepNumber());

        service.forceClose(admin, id, "The department head left the company");

        Request closed = reload(id);
        assertEquals(RequestStatus.REJECTED, closed.getStatus());
        assertEquals(List.of(StageStatus.APPROVED, StageStatus.CLOSED), stageStatuses(closed));
    }

    // ---- force close ----

    @Test
    void forceCloseRejectsTheRequestAndRecordsHistoryAndAudit() {
        Long id = submit();
        setActive(manager, false);

        service.forceClose(admin, id, "  Nobody left to approve  ");

        Request closed = reload(id);
        assertEquals(RequestStatus.REJECTED, closed.getStatus());
        assertEquals(List.of(StageStatus.CLOSED), stageStatuses(closed));
        assertFalse(isStuck(id)); // it is finished now

        var history = approvalService.historyFor(id);
        assertEquals(1, history.size());
        assertEquals("Closed by administrator", history.get(0).getActionLabel());
        assertEquals("Administrator", history.get(0).getActorRoleLabel());
        assertEquals("Nobody left to approve", history.get(0).getComment());
        assertEquals(1, history.get(0).getStepNumber());

        List<AuditLog> audit = auditLogRepository.findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_REQUEST, id);
        assertEquals(List.of(AuditService.REQUEST_SUBMITTED, AuditService.REQUEST_FORCE_CLOSED),
                audit.stream().map(AuditLog::getAction).toList());
        assertTrue(audit.get(1).getDetails().contains("Nobody left to approve"));
        assertEquals(admin.getId(), audit.get(1).getActorId());
    }

    @Test
    void forceCloseNeedsAReason() {
        Long id = submit();
        setActive(manager, false);

        assertThrows(InvalidDecisionException.class, () -> service.forceClose(admin, id, null));
        assertThrows(InvalidDecisionException.class, () -> service.forceClose(admin, id, "   "));
        assertThrows(InvalidDecisionException.class, () -> service.forceClose(admin, id, "x".repeat(1001)));

        assertEquals(RequestStatus.PENDING_APPROVAL, reload(id).getStatus());
    }

    @Test
    void forceCloseIsRefusedWhenAnApproverIsAvailable() {
        Long id = submit(); // the manager is active, so the request is not stuck

        assertThrows(InvalidDecisionException.class, () -> service.forceClose(admin, id, "Trying anyway"));

        assertEquals(RequestStatus.PENDING_APPROVAL, reload(id).getStatus());
    }

    @Test
    void forceCloseIsRefusedForRequestsThatAreNotPending() {
        Long approvedId = submit();
        approvalService.approve(approvedId, manager, null);

        assertThrows(InvalidDecisionException.class, () -> service.forceClose(admin, approvedId, "Too late"));
        assertEquals(RequestStatus.APPROVED, reload(approvedId).getStatus());
    }

    @Test
    void unknownRequestIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> service.forceClose(admin, 999_999_999L, "No such request"));
    }

    @Test
    void onlyAnActiveAdministratorCanUseTheService() {
        Long id = submit();
        setActive(manager, false);

        assertThrows(AccessDeniedException.class, () -> service.overview(employee));
        assertThrows(AccessDeniedException.class, () -> service.findStuck(manager));
        assertThrows(AccessDeniedException.class, () -> service.forceClose(employee, id, "Not allowed"));

        setActive(admin, false);
        assertThrows(AccessDeniedException.class, () -> service.findStuck(admin));
        assertEquals(RequestStatus.PENDING_APPROVAL, reload(id).getStatus());
    }

    // ---- overview ----

    @Test
    void overviewCountsMoveWithRealActivity() {
        Overview before = service.overview(admin);

        Long id = submit();
        Overview afterSubmit = service.overview(admin);
        assertEquals(before.getTotalRequests() + 1, afterSubmit.getTotalRequests());
        assertEquals(before.getPendingApproval() + 1, afterSubmit.getPendingApproval());
        assertEquals(waiting(before, "Manager") + 1, waiting(afterSubmit, "Manager"));
        assertEquals(before.getStuckCount(), afterSubmit.getStuckCount());

        setActive(manager, false);
        Overview whileStuck = service.overview(admin);
        assertEquals(afterSubmit.getStuckCount() + 1, whileStuck.getStuckCount());
        assertEquals(activeUsers(afterSubmit, "Manager") - 1, activeUsers(whileStuck, "Manager"));

        setActive(manager, true);
        approvalService.approve(id, manager, null);
        Overview afterApproval = service.overview(admin);
        assertEquals(before.getPendingApproval(), afterApproval.getPendingApproval());
        assertEquals(statusCount(before, "Approved") + 1, statusCount(afterApproval, "Approved"));
        assertEquals(0, new BigDecimal("5000")
                .compareTo(afterApproval.getApprovedValue().subtract(before.getApprovedValue())));

        List<AuditEntry> recent = afterApproval.getRecentActivity();
        assertFalse(recent.isEmpty());
        assertTrue(recent.size() <= 10);
        assertTrue(recent.get(0).getId() > recent.get(recent.size() - 1).getId()); // newest first
    }

    // ---- pure calculations ----

    @Test
    void pendingCountsUseTheCurrentStageOfEachRequest() {
        Request atManager = routed(Role.MANAGER, Role.FINANCE_OFFICER);
        Request approved = routed(Role.MANAGER);
        approved.approveCurrentStage(); // finished: no current stage
        Request atFinance = routed(Role.FINANCE_OFFICER);
        Request movedOn = routed(Role.MANAGER, Role.FINANCE_OFFICER);
        movedOn.approveCurrentStage(); // now waiting for finance

        Map<Role, Long> counts = AdminOversightService.countByCurrentRole(
                List.of(atManager, approved, atFinance, movedOn));

        assertEquals(1L, counts.get(Role.MANAGER));
        assertEquals(2L, counts.get(Role.FINANCE_OFFICER));
        assertNull(counts.get(Role.DEPARTMENT_HEAD));
    }

    @Test
    void percentagesAreRoundedAndSafeForZero() {
        assertEquals(33, AdminOversightService.percent(1, 3));
        assertEquals(67, AdminOversightService.percent(2, 3));
        assertEquals(100, AdminOversightService.percent(5, 5));
        assertEquals(0, AdminOversightService.percent(0, 0));
        assertEquals(0, AdminOversightService.percent(3, 0));
    }

    private static Request routed(Role... roles) {
        Request request = new Request("Title", "Description", RequestCategory.OTHER, new BigDecimal("100"), null);
        request.submit();
        request.routeForApproval(List.of(roles), "test routing");
        return request;
    }
}