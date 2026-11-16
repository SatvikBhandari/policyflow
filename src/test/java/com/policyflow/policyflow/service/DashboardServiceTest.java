package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.policyflow.policyflow.dto.DashboardSummary;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;

class DashboardServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private final DashboardService service = new DashboardService(null); // summarize() needs no repository

    private static Request request(String amount, RequestCategory category,
                                   RequestStatus status, LocalDateTime createdAt) {
        Request request = new Request("Title", "Description", category, new BigDecimal(amount), null);
        ReflectionTestUtils.setField(request, "status", status);
        ReflectionTestUtils.setField(request, "createdAt", createdAt);
        return request;
    }

    private static LocalDateTime at(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 10, 0);
    }

    @Test
    void emptyListGivesZeroEverywhere() {
        DashboardSummary summary = service.summarize(List.of(), TODAY);

        assertEquals(0, summary.getTotal());
        assertEquals(0, summary.getSubmitted());
        assertEquals(0, summary.getTotalAmount().signum());
        assertEquals(0, summary.getMonthDelta());
        assertTrue(summary.getRecent().isEmpty());
        assertTrue(summary.getCategories().isEmpty());
    }

    @Test
    void countsStatusesAndExcludesCancelledFromValue() {
        DashboardSummary summary = service.summarize(List.of(
                request("1000.00", RequestCategory.HARDWARE, RequestStatus.SUBMITTED, at(2026, 10, 5)),
                request("2000.00", RequestCategory.SOFTWARE, RequestStatus.SUBMITTED, at(2026, 10, 4)),
                request("500.00", RequestCategory.TRAVEL, RequestStatus.DRAFT, at(2026, 10, 3)),
                request("9999.00", RequestCategory.OTHER, RequestStatus.CANCELLED, at(2026, 10, 2))), TODAY);

        assertEquals(3, summary.getTotal());
        assertEquals(2, summary.getSubmitted());
        assertEquals(1, summary.getDrafts());
        assertEquals(1, summary.getCancelled());
        assertEquals(0, new BigDecimal("3500.00").compareTo(summary.getTotalAmount()));
    }

    @Test
    void totalRequestsDoesNotCountCancelledOnes() {
        DashboardSummary summary = service.summarize(List.of(
                request("100", RequestCategory.OTHER, RequestStatus.SUBMITTED, at(2026, 10, 5)),
                request("100", RequestCategory.OTHER, RequestStatus.CANCELLED, at(2026, 10, 4)),
                request("100", RequestCategory.OTHER, RequestStatus.CANCELLED, at(2026, 10, 3))), TODAY);

        assertEquals(1, summary.getTotal());
        assertEquals(2, summary.getCancelled());
    }

    @Test
    void comparesThisMonthWithLastMonth() {
        DashboardSummary summary = service.summarize(List.of(
                request("100", RequestCategory.OTHER, RequestStatus.SUBMITTED, at(2026, 10, 10)),
                request("100", RequestCategory.OTHER, RequestStatus.SUBMITTED, at(2026, 10, 2)),
                request("100", RequestCategory.OTHER, RequestStatus.CANCELLED, at(2026, 10, 1)), // cancelled: ignored
                request("100", RequestCategory.OTHER, RequestStatus.SUBMITTED, at(2026, 9, 28)),
                request("100", RequestCategory.OTHER, RequestStatus.SUBMITTED, at(2026, 8, 1))), TODAY); // older: ignored

        assertEquals(2, summary.getThisMonthCount());
        assertEquals(1, summary.getLastMonthCount());
        assertEquals(1, summary.getMonthDelta());
    }

    @Test
    void lastMonthIsCorrectAcrossAYearBoundary() {
        DashboardSummary summary = service.summarize(List.of(
                request("100", RequestCategory.OTHER, RequestStatus.SUBMITTED, at(2025, 12, 20))),
                LocalDate.of(2026, 1, 5));

        assertEquals(0, summary.getThisMonthCount());
        assertEquals(1, summary.getLastMonthCount());
        assertEquals(1, summary.getMonthDeltaAbs());
    }

    @Test
    void categoriesAreSortedByValueWithPercentages() {
        DashboardSummary summary = service.summarize(List.of(
                request("25000.00", RequestCategory.SOFTWARE, RequestStatus.SUBMITTED, at(2026, 10, 5)),
                request("75000.00", RequestCategory.HARDWARE, RequestStatus.SUBMITTED, at(2026, 10, 4))), TODAY);

        assertEquals(2, summary.getCategories().size());
        assertEquals("Hardware", summary.getCategories().get(0).getLabel());
        assertEquals(75, summary.getCategories().get(0).getPercent());
        assertEquals("Software", summary.getCategories().get(1).getLabel());
        assertEquals(25, summary.getCategories().get(1).getPercent());
    }

    @Test
    void recentListIsLimitedToFiveInTheGivenOrder() {
        List<Request> requests = new ArrayList<>();
        for (int day = 7; day >= 1; day--) { // newest first, as the repository returns them
            requests.add(request("100", RequestCategory.OTHER, RequestStatus.SUBMITTED, at(2026, 10, day)));
        }

        DashboardSummary summary = service.summarize(requests, TODAY);

        assertEquals(7, summary.getTotal());
        assertEquals(5, summary.getRecent().size());
        assertEquals(requests.get(0), summary.getRecent().get(0));
    }
}