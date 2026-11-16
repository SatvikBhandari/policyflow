package com.policyflow.policyflow.enums;

public enum RequestStatus {
    DRAFT("Draft", "badge-neutral"),
    SUBMITTED("Submitted", "badge-info"),
    PENDING_APPROVAL("Pending approval", "badge-warning"),
    APPROVED("Approved", "badge-success"),
    REJECTED("Rejected", "badge-danger"),
    CANCELLED("Cancelled", "badge-neutral");

    private final String label;
    private final String badgeClass;

    RequestStatus(String label, String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    public String getLabel() {
        return label;
    }

    public String getBadgeClass() {
        return badgeClass;
    }
}