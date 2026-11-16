package com.policyflow.policyflow.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;

class RequestDraftTest {

    private static Request draft() {
        return new Request("Old title", "Old description", RequestCategory.OTHER, new BigDecimal("100"), null);
    }

    @Test
    void updateDraftChangesEveryFieldAndTrimsTheText() {
        Request request = draft();

        request.updateDraft("  New title  ", "  New description  ", RequestCategory.SOFTWARE,
                new BigDecimal("2500.50"), RequestPriority.URGENT);

        assertEquals("New title", request.getTitle());
        assertEquals("New description", request.getDescription());
        assertEquals(RequestCategory.SOFTWARE, request.getCategory());
        assertEquals(0, new BigDecimal("2500.50").compareTo(request.getAmount()));
        assertEquals(RequestPriority.URGENT, request.getPriority());
        assertEquals(RequestStatus.DRAFT, request.getStatus());
    }

    @Test
    void onlyDraftsCanBeEdited() {
        Request submitted = draft();
        submitted.submit();
        assertThrows(IllegalStateException.class, () -> submitted.updateDraft(
                "Title", "Description", RequestCategory.OTHER, BigDecimal.TEN, RequestPriority.NORMAL));

        Request routed = draft();
        routed.submit();
        routed.routeForApproval(List.of(Role.MANAGER), "test routing");
        assertThrows(IllegalStateException.class, () -> routed.updateDraft(
                "Title", "Description", RequestCategory.OTHER, BigDecimal.TEN, RequestPriority.NORMAL));

        Request cancelled = draft();
        cancelled.cancel();
        assertThrows(IllegalStateException.class, () -> cancelled.updateDraft(
                "Title", "Description", RequestCategory.OTHER, BigDecimal.TEN, RequestPriority.NORMAL));
    }

    @Test
    void invalidValuesAreRejectedAndNothingChanges() {
        Request request = draft();
        BigDecimal ten = BigDecimal.TEN;

        assertThrows(IllegalArgumentException.class, () -> request.updateDraft(
                "   ", "Description", RequestCategory.TRAVEL, ten, RequestPriority.URGENT));
        assertThrows(IllegalArgumentException.class, () -> request.updateDraft(
                "Title", null, RequestCategory.TRAVEL, ten, RequestPriority.URGENT));
        assertThrows(IllegalArgumentException.class, () -> request.updateDraft(
                "Title", "Description", null, ten, RequestPriority.URGENT));
        assertThrows(IllegalArgumentException.class, () -> request.updateDraft(
                "Title", "Description", RequestCategory.TRAVEL, BigDecimal.ZERO, RequestPriority.URGENT));
        assertThrows(IllegalArgumentException.class, () -> request.updateDraft(
                "Title", "Description", RequestCategory.TRAVEL, new BigDecimal("-5"), RequestPriority.URGENT));
        assertThrows(IllegalArgumentException.class, () -> request.updateDraft(
                "Title", "Description", RequestCategory.TRAVEL, ten, null));

        // every attempt failed, so the draft is exactly as it was
        assertEquals("Old title", request.getTitle());
        assertEquals("Old description", request.getDescription());
        assertEquals(RequestCategory.OTHER, request.getCategory());
        assertEquals(0, new BigDecimal("100").compareTo(request.getAmount()));
        assertEquals(RequestPriority.NORMAL, request.getPriority());
    }

    @Test
    void aDraftCanBeSubmittedAfterEditing() {
        Request request = draft();
        request.updateDraft("Edited", "Edited description", RequestCategory.HARDWARE,
                new BigDecimal("900"), RequestPriority.LOW);

        request.submit();

        assertEquals(RequestStatus.SUBMITTED, request.getStatus());
        assertEquals("Edited", request.getTitle());
    }
}