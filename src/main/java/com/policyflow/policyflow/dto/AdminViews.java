package com.policyflow.policyflow.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only view objects for the administrator pages. */
public final class AdminViews {

    private AdminViews() {
    }

    /** One row of the audit log. */
    public static class AuditEntry {
        private final Long id;
        private final LocalDateTime createdAt;
        private final String actionLabel;
        private final String actorEmail;
        private final String entityLabel;
        private final Long entityId;
        private final String details;
        private final String link;

        public AuditEntry(Long id, LocalDateTime createdAt, String actionLabel, String actorEmail,
                          String entityLabel, Long entityId, String details, String link) {
            this.id = id;
            this.createdAt = createdAt;
            this.actionLabel = actionLabel;
            this.actorEmail = actorEmail;
            this.entityLabel = entityLabel;
            this.entityId = entityId;
            this.details = details;
            this.link = link;
        }

        public Long getId() { return id; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public String getActionLabel() { return actionLabel; }
        public String getActorEmail() { return actorEmail; }
        public String getEntityLabel() { return entityLabel; }
        public Long getEntityId() { return entityId; }
        public String getDetails() { return details; }
        /** A page where the admin can open the entity, or null when there is none. */
        public String getLink() { return link; }
    }

    /** A pending request whose current stage has nobody who may decide it. */
    public static class StuckRequest {
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
        private final String waitingRoleLabel;
        private final String explanation;

        public StuckRequest(Long requestId, String reference, String title, String categoryLabel,
                            BigDecimal amount, String requesterName, String departmentName,
                            LocalDateTime submittedAt, int stepNumber, int totalSteps,
                            String waitingRoleLabel, String explanation) {
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
            this.waitingRoleLabel = waitingRoleLabel;
            this.explanation = explanation;
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
        public String getWaitingRoleLabel() { return waitingRoleLabel; }
        public String getExplanation() { return explanation; }
    }

    /** Number of requests in one status, with its share of all requests as a whole percentage. */
    public static class StatusCount {
        private final String label;
        private final String badgeClass;
        private final long count;
        private final int percent;

        public StatusCount(String label, String badgeClass, long count, int percent) {
            this.label = label;
            this.badgeClass = badgeClass;
            this.count = count;
            this.percent = percent;
        }

        public String getLabel() { return label; }
        public String getBadgeClass() { return badgeClass; }
        public long getCount() { return count; }
        public int getPercent() { return percent; }
    }

    /** Number of pending requests currently waiting for one approver role. */
    public static class RoleCount {
        private final String label;
        private final long count;

        public RoleCount(String label, long count) {
            this.label = label;
            this.count = count;
        }

        public String getLabel() { return label; }
        public long getCount() { return count; }
    }

    /** Active and total users of one role. */
    public static class RoleUsers {
        private final String label;
        private final long active;
        private final long total;

        public RoleUsers(String label, long active, long total) {
            this.label = label;
            this.active = active;
            this.total = total;
        }

        public String getLabel() { return label; }
        public long getActive() { return active; }
        public long getTotal() { return total; }
    }

    /** Everything the overview page shows. */
    public static class Overview {
        private final long totalRequests;
        private final long pendingApproval;
        private final long stuckCount;
        private final BigDecimal approvedValue;
        private final long totalUsers;
        private final long activeUsers;
        private final long activePolicies;
        private final long totalPolicies;
        private final List<StatusCount> statusCounts;
        private final List<RoleCount> pendingByRole;
        private final List<RoleUsers> usersByRole;
        private final List<AuditEntry> recentActivity;

        public Overview(long totalRequests, long pendingApproval, long stuckCount, BigDecimal approvedValue,
                        long totalUsers, long activeUsers, long activePolicies, long totalPolicies,
                        List<StatusCount> statusCounts, List<RoleCount> pendingByRole,
                        List<RoleUsers> usersByRole, List<AuditEntry> recentActivity) {
            this.totalRequests = totalRequests;
            this.pendingApproval = pendingApproval;
            this.stuckCount = stuckCount;
            this.approvedValue = approvedValue;
            this.totalUsers = totalUsers;
            this.activeUsers = activeUsers;
            this.activePolicies = activePolicies;
            this.totalPolicies = totalPolicies;
            this.statusCounts = statusCounts;
            this.pendingByRole = pendingByRole;
            this.usersByRole = usersByRole;
            this.recentActivity = recentActivity;
        }

        public long getTotalRequests() { return totalRequests; }
        public long getPendingApproval() { return pendingApproval; }
        public long getStuckCount() { return stuckCount; }
        public BigDecimal getApprovedValue() { return approvedValue; }
        public long getTotalUsers() { return totalUsers; }
        public long getActiveUsers() { return activeUsers; }
        public long getActivePolicies() { return activePolicies; }
        public long getTotalPolicies() { return totalPolicies; }
        public List<StatusCount> getStatusCounts() { return statusCounts; }
        public List<RoleCount> getPendingByRole() { return pendingByRole; }
        public List<RoleUsers> getUsersByRole() { return usersByRole; }
        public List<AuditEntry> getRecentActivity() { return recentActivity; }
    }
}