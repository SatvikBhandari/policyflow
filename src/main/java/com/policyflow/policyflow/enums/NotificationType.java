package com.policyflow.policyflow.enums;

/** What a notification is about. The label and badge colour are used by the Notifications page. */
public enum NotificationType {
    APPROVAL_REQUESTED("Needs your decision", "badge-warning"),
    STAGE_APPROVED("Progress", "badge-info"),
    REQUEST_APPROVED("Approved", "badge-success"),
    REQUEST_REJECTED("Rejected", "badge-danger"),
    REQUEST_CLOSED("Closed", "badge-danger"),
    REQUEST_WITHDRAWN("Withdrawn", "badge-neutral");

    private final String label;
    private final String badgeClass;

    NotificationType(String label, String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    public String getLabel() { return label; }
    public String getBadgeClass() { return badgeClass; }
}