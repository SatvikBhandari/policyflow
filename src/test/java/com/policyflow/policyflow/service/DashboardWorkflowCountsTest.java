package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.policyflow.policyflow.dto.DashboardSummary;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;

class DashboardWorkflowCountsTest {

    private final DashboardService service = new DashboardService(null); // summarize() needs no repository

    private static Request request(RequestStatus status) {
        Request request = new Request("Title", "Description", RequestCategory.OTHER, new BigDecimal("100"), null);
        ReflectionTestUtils.setField(request, "status", status);
        ReflectionTestUtils.setField(request, "createdAt", LocalDateTime.of(2026, 10, 5, 10, 0));
        return request;
    }

    @Test
    void everyWorkflowStatusIsCountedSeparately() {
        DashboardSummary summary = service.summarize(List.of(
                request(RequestStatus.DRAFT),
                request(RequestStatus.SUBMITTED),
                request(RequestStatus.PENDING_APPROVAL),
                request(RequestStatus.PENDING_APPROVAL),
                request(RequestStatus.APPROVED),
                request(RequestStatus.REJECTED),
                request(RequestStatus.CANCELLED)), LocalDate.of(2026, 10, 19));

        assertEquals(6, summary.getTotal());
        assertEquals(1, summary.getDrafts());
        assertEquals(1, summary.getSubmitted());
        assertEquals(2, summary.getPendingApproval());
        assertEquals(1, summary.getApproved());
        assertEquals(1, summary.getRejected());
        assertEquals(1, summary.getCancelled());
    }
}