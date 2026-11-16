package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.ApprovalViews.DecidedItem;
import com.policyflow.policyflow.dto.ApprovalViews.QueueItem;
import com.policyflow.policyflow.dto.ApprovalViews.Review;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
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
@Transactional // everything is rolled back, so your real data and policies are untouched
class ApprovalWorkflowTest {

    @Autowired private ApprovalService approvalService;
    @Autowired private RequestService requestService;
    @Autowired private RequestRepository requestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;

    private User employee;
    private User colleague;
    private User manager;
    private User finance;
    private User head;
    private User outsiderManager;
    private Department department;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository);

        department = departmentRepository.save(new Department("Workflow Test", "WFTEST"));
        Department other = departmentRepository.save(new Department("Workflow Other", "WFOTHER"));

        employee = user("wf-employee", Role.EMPLOYEE, department);
        colleague = user("wf-colleague", Role.EMPLOYEE, department);
        manager = user("wf-manager", Role.MANAGER, department);
        finance = user("wf-finance", Role.FINANCE_OFFICER, other);
        head = user("wf-head", Role.DEPARTMENT_HEAD, department);
        outsiderManager = user("wf-outsider", Role.MANAGER, other);
    }

    // ---- helpers ----

    private User user(String prefix, Role role, Department dept) {
        return WorkflowFixtures.user(userRepository, prefix, role, dept);
    }

    private void policy(String name, Role... roles) {
        WorkflowFixtures.policy(policyRepository, name, roles);
    }

    private Request submit() {
        return requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee);
    }

    /** Reads the request again from MySQL (not from memory). */
    private Request reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return requestRepository.findDetailedById(id).orElseThrow();
    }

    private List<StageStatus> stageStatuses(Request request) {
        return request.getStages().stream().map(RequestStage::getStatus).toList();
    }

    private List<Long> queueIds(User approver) {
        return approvalService.pendingFor(approver).stream().map(QueueItem::getRequestId).toList();
    }

    // ---- approving ----

    @Test
    void threeStageChainAdvancesInOrder() {
        policy("WF three", Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD);
        Long id = submit().getId();

        approvalService.approve(id, manager, "Looks fine");
        Request afterManager = reload(id);
        assertEquals(RequestStatus.PENDING_APPROVAL, afterManager.getStatus());
        assertEquals(List.of(StageStatus.APPROVED, StageStatus.PENDING, StageStatus.WAITING),
                stageStatuses(afterManager));
        assertEquals(Role.FINANCE_OFFICER, afterManager.getCurrentStage().getApproverRole());

        approvalService.approve(id, finance, null);
        Request afterFinance = reload(id);
        assertEquals(List.of(StageStatus.APPROVED, StageStatus.APPROVED, StageStatus.PENDING),
                stageStatuses(afterFinance));

        approvalService.approve(id, head, "Final approval");
        Request done = reload(id);
        assertEquals(RequestStatus.APPROVED, done.getStatus());
        assertEquals(List.of(StageStatus.APPROVED, StageStatus.APPROVED, StageStatus.APPROVED), stageStatuses(done));
        assertEquals(3, approvalService.historyFor(id).size());
    }

    @Test
    void approverCannotActOutOfTurn() {
        policy("WF chain", Role.MANAGER, Role.FINANCE_OFFICER);
        Long id = submit().getId();

        // Finance tries to approve before the manager has done so
        assertThrows(ResourceNotFoundException.class, () -> approvalService.approve(id, finance, "early"));

        Request unchanged = reload(id);
        assertEquals(List.of(StageStatus.PENDING, StageStatus.WAITING), stageStatuses(unchanged));
    }

    // ---- rejecting ----

    @Test
    void rejectionRequiresAComment() {
        policy("WF manager", Role.MANAGER);
        Long id = submit().getId();

        assertThrows(InvalidDecisionException.class, () -> approvalService.reject(id, manager, "   "));
        assertThrows(InvalidDecisionException.class, () -> approvalService.reject(id, manager, null));

        assertEquals(RequestStatus.PENDING_APPROVAL, reload(id).getStatus());
    }

    @Test
    void rejectionEndsTheWorkflowAndClosesLaterStages() {
        policy("WF chain", Role.MANAGER, Role.FINANCE_OFFICER);
        Long id = submit().getId();

        approvalService.reject(id, manager, "Over budget");

        Request rejected = reload(id);
        assertEquals(RequestStatus.REJECTED, rejected.getStatus());
        assertEquals(List.of(StageStatus.REJECTED, StageStatus.CLOSED), stageStatuses(rejected));
        assertEquals("Over budget", approvalService.historyFor(id).get(0).getComment());
        assertFalse(queueIds(finance).contains(id));
        assertThrows(ResourceNotFoundException.class, () -> approvalService.approve(id, finance, null));
    }

    // ---- who may decide ----

    @Test
    void decidingTwiceIsRefused() {
        policy("WF manager", Role.MANAGER);
        Long id = submit().getId();

        approvalService.approve(id, manager, null);

        assertThrows(InvalidDecisionException.class, () -> approvalService.approve(id, manager, null));
        assertThrows(InvalidDecisionException.class, () -> approvalService.reject(id, manager, "changed my mind"));
        assertEquals(RequestStatus.APPROVED, reload(id).getStatus());
    }

    @Test
    void approverFromAnotherDepartmentCannotDecide() {
        policy("WF manager", Role.MANAGER);
        Long id = submit().getId();

        assertThrows(ResourceNotFoundException.class, () -> approvalService.approve(id, outsiderManager, null));
        assertEquals(RequestStatus.PENDING_APPROVAL, reload(id).getStatus());
    }

    @Test
    void requesterCannotApproveTheirOwnRequest() {
        policy("WF manager", Role.MANAGER);
        // A manager raises a request; the other manager in the department must approve it
        User managerRequester = user("wf-manager-requester", Role.MANAGER, department);
        Long id = requestService.createAndSubmit(WorkflowFixtures.form("5000"), managerRequester).getId();

        assertThrows(ResourceNotFoundException.class, () -> approvalService.approve(id, managerRequester, "self"));
        assertFalse(queueIds(managerRequester).contains(id));
        assertTrue(queueIds(manager).contains(id));
    }

    @Test
    void anInactiveApproverCannotDecide() {
        policy("WF manager", Role.MANAGER);
        Long id = submit().getId();
        manager.setActive(false);
        userRepository.saveAndFlush(manager);

        assertThrows(ResourceNotFoundException.class, () -> approvalService.approve(id, manager, "ok"));
    }

    // ---- queues, review and history ----

    @Test
    void queueShowsRequestsOnlyToTheCurrentApprover() {
        policy("WF chain", Role.MANAGER, Role.FINANCE_OFFICER);
        Long id = submit().getId();

        assertTrue(queueIds(manager).contains(id));
        assertFalse(queueIds(outsiderManager).contains(id));
        assertFalse(queueIds(finance).contains(id));   // not their turn yet
        assertFalse(queueIds(head).contains(id));
        assertFalse(queueIds(employee).contains(id));  // employees have no approval queue

        approvalService.approve(id, manager, null);

        assertFalse(queueIds(manager).contains(id));
        assertTrue(queueIds(finance).contains(id));
    }

    @Test
    void decidedRequestsStayVisibleToTheApprover() {
        policy("WF manager", Role.MANAGER);
        Long id = submit().getId();
        approvalService.approve(id, manager, "Approved for onboarding");

        List<DecidedItem> decided = approvalService.recentlyDecidedBy(manager);
        assertTrue(decided.stream().anyMatch(
                item -> item.getRequestId().equals(id) && "Approved".equals(item.getActionLabel())));

        Review review = approvalService.getReview(id, manager);
        assertFalse(review.isCanDecide());
        assertEquals(1, review.getHistory().size());
        assertEquals("Approved for onboarding", review.getHistory().get(0).getComment());

        assertThrows(ResourceNotFoundException.class, () -> approvalService.getReview(id, outsiderManager));
    }

    @Test
    void decisionsWriteAuditEntries() {
        policy("WF manager", Role.MANAGER);
        Long id = submit().getId();
        approvalService.approve(id, manager, "ok");

        List<String> actions = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_REQUEST, id)
                .stream().map(AuditLog::getAction).toList();

        assertEquals(List.of("REQUEST_SUBMITTED", "STAGE_APPROVED", "REQUEST_APPROVED"), actions);
    }

    // ---- concurrency ----

    @Test
    void concurrentDecisionIsRejectedByOptimisticLocking() {
        policy("WF manager", Role.MANAGER);
        Long id = submit().getId();
        entityManager.flush();
        entityManager.clear();

        // This transaction loads the request and its stage (version 0) ...
        Request loaded = requestRepository.findDetailedById(id).orElseThrow();
        Long stageId = loaded.getCurrentStage().getId();

        // ... and meanwhile another transaction changes the same stage row (simulated with direct SQL)
        entityManager.createNativeQuery("update request_stages set version = version + 1 where id = :id")
                .setParameter("id", stageId)
                .executeUpdate();

        // Our decision is based on stale data, so the version check must refuse it
        assertThrows(OptimisticLockingFailureException.class,
                () -> approvalService.approve(id, manager, "late"));
    }

    // ---- cancelling ----

    @Test
    void cancellingClosesStagesAndRecordsHistory() {
        policy("WF chain", Role.MANAGER, Role.FINANCE_OFFICER);
        Long id = submit().getId();

        requestService.cancel(id, employee, "No longer needed");

        Request cancelled = reload(id);
        assertEquals(RequestStatus.CANCELLED, cancelled.getStatus());
        assertEquals(List.of(StageStatus.CLOSED, StageStatus.CLOSED), stageStatuses(cancelled));
        assertEquals("Cancelled", approvalService.historyFor(id).get(0).getActionLabel());
        assertEquals("No longer needed", approvalService.historyFor(id).get(0).getComment());
        assertFalse(queueIds(manager).contains(id));
        assertThrows(InvalidDecisionException.class, () -> approvalService.approve(id, manager, null)); // asked earlier, so a clear message instead of a 404

        List<String> actions = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_REQUEST, id)
                .stream().map(AuditLog::getAction).toList();
        assertTrue(actions.contains("REQUEST_CANCELLED"));
    }

    @Test
    void cancellingIsRefusedOnceApprovalHasStarted() {
        policy("WF chain", Role.MANAGER, Role.FINANCE_OFFICER);
        Long id = submit().getId();
        approvalService.approve(id, manager, null);

        assertThrows(IllegalStateException.class, () -> requestService.cancel(id, employee, "too late"));
        assertEquals(RequestStatus.PENDING_APPROVAL, reload(id).getStatus());
    }

    @Test
    void onlyTheOwnerCanCancel() {
        policy("WF manager", Role.MANAGER);
        Long id = submit().getId();

        assertThrows(ResourceNotFoundException.class, () -> requestService.cancel(id, colleague, "not mine"));
        assertEquals(RequestStatus.PENDING_APPROVAL, reload(id).getStatus());
    }

    @Test
    void finishedRequestsCannotBeCancelled() {
        policy("WF manager", Role.MANAGER);
        Long approvedId = submit().getId();
        approvalService.approve(approvedId, manager, null);
        Long cancelledId = submit().getId();
        requestService.cancel(cancelledId, employee, null);

        assertThrows(IllegalStateException.class, () -> requestService.cancel(approvedId, employee, null));
        assertThrows(IllegalStateException.class, () -> requestService.cancel(cancelledId, employee, null));
    }
}