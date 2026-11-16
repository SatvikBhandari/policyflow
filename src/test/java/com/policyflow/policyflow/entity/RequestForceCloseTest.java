package com.policyflow.policyflow.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.enums.StageStatus;

class RequestForceCloseTest {

    private static Request routed(Role... roles) {
        Request request = new Request("Title", "Description", RequestCategory.OTHER, new BigDecimal("100"), null);
        request.submit();
        request.routeForApproval(List.of(roles), "test routing");
        return request;
    }

    private static List<StageStatus> stageStatuses(Request request) {
        return request.getStages().stream().map(RequestStage::getStatus).toList();
    }

    @Test
    void forceCloseRejectsTheRequestAndClosesEveryOpenStage() {
        Request request = routed(Role.MANAGER, Role.FINANCE_OFFICER);

        request.forceClose();

        assertEquals(RequestStatus.REJECTED, request.getStatus());
        assertEquals(List.of(StageStatus.CLOSED, StageStatus.CLOSED), stageStatuses(request));
    }

    @Test
    void forceCloseKeepsEarlierApprovals() {
        Request request = routed(Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD);
        request.approveCurrentStage();

        request.forceClose();

        assertEquals(RequestStatus.REJECTED, request.getStatus());
        assertEquals(List.of(StageStatus.APPROVED, StageStatus.CLOSED, StageStatus.CLOSED), stageStatuses(request));
    }

    @Test
    void forceCloseIsRefusedUnlessTheRequestIsPendingApproval() {
        Request draft = new Request("Title", "Description", RequestCategory.OTHER, new BigDecimal("100"), null);
        assertThrows(IllegalStateException.class, draft::forceClose);

        Request submittedOnly = new Request("Title", "Description", RequestCategory.OTHER, new BigDecimal("100"), null);
        submittedOnly.submit();
        assertThrows(IllegalStateException.class, submittedOnly::forceClose);

        Request approved = routed(Role.MANAGER);
        approved.approveCurrentStage();
        assertThrows(IllegalStateException.class, approved::forceClose);

        Request rejected = routed(Role.MANAGER);
        rejected.rejectCurrentStage();
        assertThrows(IllegalStateException.class, rejected::forceClose);

        Request cancelled = routed(Role.MANAGER);
        cancelled.cancel();
        assertThrows(IllegalStateException.class, cancelled::forceClose);
    }

    @Test
    void aForceClosedRequestCannotBeDecidedOrCancelledAgain() {
        Request request = routed(Role.MANAGER);
        request.forceClose();

        assertThrows(IllegalStateException.class, request::approveCurrentStage);
        assertThrows(IllegalStateException.class, request::rejectCurrentStage);
        assertThrows(IllegalStateException.class, request::cancel);
        assertThrows(IllegalStateException.class, request::forceClose);
    }
}