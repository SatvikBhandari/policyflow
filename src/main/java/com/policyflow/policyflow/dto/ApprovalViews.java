package com.policyflow.policyflow.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.enums.RequestPriority;

/** Read-only view objects for the approval pages. */
public final class ApprovalViews {

    private ApprovalViews() {
    }

    /** One row of an approver's queue. */
    public static class QueueItem {
        private final Long requestId;
        private final String reference;
        private final String title;
        private final String categoryLabel;
        private final BigDecimal amount;
        private final String requesterName;
        private final String departmentName;
        private final LocalDateTime submittedAt;
        private final int stepNumber;
        private final int totalSteps;
        private RequestPriority priority = RequestPriority.NORMAL;

        public QueueItem(Long requestId, String reference, String title, String categoryLabel,
                         BigDecimal amount, String requesterName, String departmentName,
                         LocalDateTime submittedAt, int stepNumber, int totalSteps) {
            this.requestId = requestId;
            this.reference = reference;
            this.title = title;
            this.categoryLabel = categoryLabel;
            this.amount = amount;
            this.requesterName = requesterName;
            this.departmentName = departmentName;
            this.submittedAt = submittedAt;
            this.stepNumber = stepNumber;
            this.totalSteps = totalSteps;
        }

        public Long getRequestId() { return requestId; }
        public String getReference() { return reference; }
        public String getTitle() { return title; }
        public String getCategoryLabel() { return categoryLabel; }
        public BigDecimal getAmount() { return amount; }
        public String getRequesterName() { return requesterName; }
        public String getDepartmentName() { return departmentName; }
        public LocalDateTime getSubmittedAt() { return submittedAt; }
        public int getStepNumber() { return stepNumber; }
        public int getTotalSteps() { return totalSteps; }

        public QueueItem(Long requestId, String reference, String title, String categoryLabel,
                         BigDecimal amount, String requesterName, String departmentName,
                         LocalDateTime submittedAt, int stepNumber, int totalSteps, RequestPriority priority) {
            this(requestId, reference, title, categoryLabel, amount, requesterName, departmentName,
                    submittedAt, stepNumber, totalSteps);
            this.priority = priority == null ? RequestPriority.NORMAL : priority;
        }

        public RequestPriority getPriority() { return priority; }
        public boolean isUrgent() { return priority == RequestPriority.URGENT; }
    }

    /** A request this approver decided earlier. */
    public static class DecidedItem {
        private final Long requestId;
        private final String reference;
        private final String title;
        private final String actionLabel;
        private final String actionBadgeClass;
        private final String comment;
        private final LocalDateTime decidedAt;
        private final String requestStatusLabel;
        private final String requestStatusBadgeClass;

        public DecidedItem(Long requestId, String reference, String title, String actionLabel,
                           String actionBadgeClass, String comment, LocalDateTime decidedAt,
                           String requestStatusLabel, String requestStatusBadgeClass) {
            this.requestId = requestId;
            this.reference = reference;
            this.title = title;
            this.actionLabel = actionLabel;
            this.actionBadgeClass = actionBadgeClass;
            this.comment = comment;
            this.decidedAt = decidedAt;
            this.requestStatusLabel = requestStatusLabel;
            this.requestStatusBadgeClass = requestStatusBadgeClass;
        }

        public Long getRequestId() { return requestId; }
        public String getReference() { return reference; }
        public String getTitle() { return title; }
        public String getActionLabel() { return actionLabel; }
        public String getActionBadgeClass() { return actionBadgeClass; }
        public String getComment() { return comment; }
        public LocalDateTime getDecidedAt() { return decidedAt; }
        public String getRequestStatusLabel() { return requestStatusLabel; }
        public String getRequestStatusBadgeClass() { return requestStatusBadgeClass; }
    }

    /** One entry in a request's approval history. */
    public static class HistoryEntry {
        private final Integer stepNumber;
        private final String actorName;
        private final String actorRoleLabel;
        private final String actionLabel;
        private final String badgeClass;
        private final String comment;
        private final LocalDateTime createdAt;

        public HistoryEntry(Integer stepNumber, String actorName, String actorRoleLabel,
                            String actionLabel, String badgeClass, String comment, LocalDateTime createdAt) {
            this.stepNumber = stepNumber;
            this.actorName = actorName;
            this.actorRoleLabel = actorRoleLabel;
            this.actionLabel = actionLabel;
            this.badgeClass = badgeClass;
            this.comment = comment;
            this.createdAt = createdAt;
        }

        public Integer getStepNumber() { return stepNumber; }
        public String getActorName() { return actorName; }
        public String getActorRoleLabel() { return actorRoleLabel; }
        public String getActionLabel() { return actionLabel; }
        public String getBadgeClass() { return badgeClass; }
        public String getComment() { return comment; }
        public LocalDateTime getCreatedAt() { return createdAt; }
    }

    /** Everything the review page needs. */
    public static class Review {
        private final Request request;
        private final List<HistoryEntry> history;
        private final boolean canDecide;
        private String note;

        public Review(Request request, List<HistoryEntry> history, boolean canDecide) {
            this.request = request;
            this.history = history;
            this.canDecide = canDecide;
        }

        public Request getRequest() { return request; }
        public List<HistoryEntry> getHistory() { return history; }
        public boolean isCanDecide() { return canDecide; }

        public Review(Request request, List<HistoryEntry> history, boolean canDecide, String note) {
            this(request, history, canDecide);
            this.note = note;
        }

        /** Why the viewer cannot decide, for example who already decided. Null when they can decide. */
        public String getNote() { return note; }
    }
}