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
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.ApprovalViews.Review;
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
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.WorkflowFixtures;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional // everything is rolled back, so your real data and policies are untouched
class LateApproverTest {

    @Autowired private ApprovalService approvalService;
    @Autowired private RequestService requestService;
    @Autowired private RequestRepository requestRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private EntityManager entityManager;

    private Department department;
    private User employee;
    private User manager;
    private User secondManager;
    private User outsiderManager;
    private User finance;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository); // only the policy created here applies
        WorkflowFixtures.policy(policyRepository, "LA manager and finance", Role.MANAGER, Role.FINANCE_OFFICER);

        department = departmentRepository.save(new Department("Late Test", "LATETST"));
        Department other = departmentRepository.save(new Department("Late Other", "LATEOTH"));
        employee = WorkflowFixtures.user(userRepository, "la-employee", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "la-manager", Role.MANAGER, department);
        secondManager = WorkflowFixtures.user(userRepository, "la-manager2", Role.MANAGER, department);
        outsiderManager = WorkflowFixtures.user(userRepository, "la-outsider", Role.MANAGER, other);
        finance = WorkflowFixtures.user(userRepository, "la-finance", Role.FINANCE_OFFICER, other);
    }

    // ---- helpers ----

    private Long submit() {
        return requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee).getId();
    }

    private Request reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return requestRepository.findDetailedById(id).orElseThrow();
    }

    // ---- the reported situation ----

    @Test
    void theSecondManagerCanOpenAnAlreadyApprovedRequestReadOnly() {
        Long id = submit();
        approvalService.approve(id, manager, "Looks fine");

        Review review = approvalService.getReview(id, secondManager);

        assertFalse(review.isCanDecide());
        assertTrue(review.getNote().contains(manager.getFullName()));
        assertTrue(review.getNote().contains("approved"));
        assertTrue(review.getNote().contains("waiting for Finance Officer"));
        assertEquals(1, review.getHistory().size());
    }

    @Test
    void theNoteSaysWhoRejectedIt() {
        Long id = submit();
        approvalService.reject(id, manager, "Over budget");

        Review review = approvalService.getReview(id, secondManager);

        assertFalse(review.isCanDecide());
        assertTrue(review.getNote().contains("rejected"));
        assertTrue(review.getNote().contains(manager.getFullName()));
        assertFalse(review.getNote().contains("waiting")); // it is finished
    }

    @Test
    void aLateDecisionGetsAFriendlyMessageInsteadOfNotFound() {
        Long id = submit();
        approvalService.approve(id, manager, null);

        InvalidDecisionException approveFailure = assertThrows(InvalidDecisionException.class,
                () -> approvalService.approve(id, secondManager, "me too"));
        assertTrue(approveFailure.getMessage().contains(manager.getFullName()));
        assertTrue(approveFailure.getMessage().contains("not recorded"));

        assertThrows(InvalidDecisionException.class,
                () -> approvalService.reject(id, secondManager, "me too"));
    }

    @Test
    void aLateDecisionChangesNothing() {
        Long id = submit();
        approvalService.approve(id, manager, null);

        assertThrows(InvalidDecisionException.class, () -> approvalService.approve(id, secondManager, "late"));
        assertThrows(InvalidDecisionException.class, () -> approvalService.reject(id, secondManager, "late"));

        Request unchanged = reload(id);
        assertEquals(RequestStatus.PENDING_APPROVAL, unchanged.getStatus());
        assertEquals(List.of(StageStatus.APPROVED, StageStatus.PENDING),
                unchanged.getStages().stream().map(RequestStage::getStatus).toList());
        assertEquals(1, approvalService.historyFor(id).size());
    }

    @Test
    void theApproverWhoDecidedSeesThatItWasThem() {
        Long id = submit();
        approvalService.approve(id, manager, null);

        Review review = approvalService.getReview(id, manager);

        assertFalse(review.isCanDecide());
        assertTrue(review.getNote().contains("by you"));
    }

    @Test
    void aCancelledRequestTellsANotifiedApproverWhoCancelledIt() {
        Long id = submit();
        requestService.cancel(id, employee, "No longer needed");

        Review review = approvalService.getReview(id, secondManager);

        assertFalse(review.isCanDecide());
        assertTrue(review.getNote().contains("cancelled"));
        assertTrue(review.getNote().contains(employee.getFullName()));
    }

    // ---- who must still get "not found" ----

    @Test
    void aManagerFromAnotherDepartmentStillGetsNotFound() {
        Long id = submit();
        approvalService.approve(id, manager, null);

        assertThrows(ResourceNotFoundException.class, () -> approvalService.getReview(id, outsiderManager));
        assertThrows(ResourceNotFoundException.class, () -> approvalService.approve(id, outsiderManager, null));
    }

    @Test
    void anApproverWhoWasNeverAskedStillGetsNotFound() {
        Long id = submit();
        User lateJoiner = WorkflowFixtures.user(userRepository, "la-latejoiner", Role.MANAGER, department);
        approvalService.approve(id, manager, null); // the manager step is finished; the late joiner was never notified

        assertThrows(ResourceNotFoundException.class, () -> approvalService.getReview(id, lateJoiner));
        assertThrows(ResourceNotFoundException.class, () -> approvalService.approve(id, lateJoiner, null));
    }

    @Test
    void financeCannotOpenTheRequestBeforeItsTurnButCanAfterwards() {
        Long id = submit();

        assertThrows(ResourceNotFoundException.class, () -> approvalService.getReview(id, finance));

        approvalService.approve(id, manager, null); // now finance is asked
        assertTrue(approvalService.getReview(id, finance).isCanDecide());
    }

    @Test
    void aDeactivatedApproverLosesTheReadOnlyAccess() {
        Long id = submit();
        approvalService.approve(id, manager, null);
        secondManager.setActive(false);
        userRepository.saveAndFlush(secondManager);

        assertThrows(ResourceNotFoundException.class, () -> approvalService.getReview(id, secondManager));
        assertThrows(ResourceNotFoundException.class, () -> approvalService.approve(id, secondManager, null));
    }
}