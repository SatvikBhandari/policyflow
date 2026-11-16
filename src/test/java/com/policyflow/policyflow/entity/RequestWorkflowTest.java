package com.policyflow.policyflow.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.enums.StageStatus;

class RequestWorkflowTest {

    private static Request submitted() {
        Request request = new Request("Title", "Description", RequestCategory.OTHER, new BigDecimal("100"), null);
        request.submit();
        return request;
    }

    private static Request routed(Role... roles) {
        Request request = submitted();
        request.routeForApproval(List.of(roles), "test routing");
        return request;
    }

    private static List<StageStatus> stageStatuses(Request request) {
        return request.getStages().stream().map(RequestStage::getStatus).toList();
    }

    @Test
    void approvingTheOnlyStageApprovesTheRequest() {
        Request request = routed(Role.MANAGER);

        RequestStage decided = request.approveCurrentStage();

        assertEquals(StageStatus.APPROVED, decided.getStatus());
        assertEquals(RequestStatus.APPROVED, request.getStatus());
        assertNull(request.getCurrentStage());
    }

    @Test
    void approvingAStageActivatesTheNextOne() {
        Request request = routed(Role.MANAGER, Role.FINANCE_OFFICER);

        request.approveCurrentStage();

        assertEquals(RequestStatus.PENDING_APPROVAL, request.getStatus());
        assertEquals(List.of(StageStatus.APPROVED, StageStatus.PENDING), stageStatuses(request));
        assertEquals(Role.FINANCE_OFFICER, request.getCurrentStage().getApproverRole());
    }

    @Test
    void approvingTheLastStageApprovesTheRequest() {
        Request request = routed(Role.MANAGER, Role.FINANCE_OFFICER);

        request.approveCurrentStage();
        request.approveCurrentStage();

        assertEquals(RequestStatus.APPROVED, request.getStatus());
        assertEquals(List.of(StageStatus.APPROVED, StageStatus.APPROVED), stageStatuses(request));
    }

    @Test
    void rejectingClosesLaterStagesAndRejectsTheRequest() {
        Request request = routed(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD);

        RequestStage decided = request.rejectCurrentStage();

        assertEquals(StageStatus.REJECTED, decided.getStatus());
        assertEquals(RequestStatus.REJECTED, request.getStatus());
        assertEquals(List.of(StageStatus.REJECTED, StageStatus.CLOSED, StageStatus.CLOSED), stageStatuses(request));
    }

    @Test
    void approvedRequestsCannotBeDecidedAgain() {
        Request request = routed(Role.MANAGER);
        request.approveCurrentStage();

        assertThrows(IllegalStateException.class, request::approveCurrentStage);
        assertThrows(IllegalStateException.class, request::rejectCurrentStage);
    }

    @Test
    void rejectedRequestsCannotBeDecidedAgain() {
        Request request = routed(Role.MANAGER, Role.FINANCE_OFFICER);
        request.rejectCurrentStage();

        assertThrows(IllegalStateException.class, request::approveCurrentStage);
        assertThrows(IllegalStateException.class, request::rejectCurrentStage);
    }

    @Test
    void cancellingClosesEveryOpenStage() {
        Request request = routed(Role.MANAGER, Role.FINANCE_OFFICER);

        request.cancel();

        assertEquals(RequestStatus.CANCELLED, request.getStatus());
        assertEquals(List.of(StageStatus.CLOSED, StageStatus.CLOSED), stageStatuses(request));
    }

    @Test
    void cancellingIsRefusedOnceAnApprovalHasBeenGiven() {
        Request request = routed(Role.MANAGER, Role.FINANCE_OFFICER);
        request.approveCurrentStage();

        assertFalse(request.canBeCancelled());
        assertThrows(IllegalStateException.class, request::cancel);
        assertEquals(RequestStatus.PENDING_APPROVAL, request.getStatus());
    }

    @Test
    void submittedRequestWithoutStagesCanBeCancelled() {
        Request legacy = submitted(); // like a request submitted before routing existed

        assertTrue(legacy.canBeCancelled());
        legacy.cancel();

        assertEquals(RequestStatus.CANCELLED, legacy.getStatus());
    }

    @Test
    void finishedRequestsCannotBeCancelled() {
        Request approved = routed(Role.MANAGER);
        approved.approveCurrentStage();
        Request rejected = routed(Role.MANAGER);
        rejected.rejectCurrentStage();
        Request cancelled = routed(Role.MANAGER);
        cancelled.cancel();

        assertFalse(approved.canBeCancelled());
        assertFalse(rejected.canBeCancelled());
        assertFalse(cancelled.canBeCancelled());
        assertThrows(IllegalStateException.class, cancelled::cancel);
    }
}