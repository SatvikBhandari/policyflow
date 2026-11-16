package com.policyflow.policyflow.enums;

/** What a person did to a request. */
public enum ActionType {
    APPROVED("Approved", "badge-success"),
    REJECTED("Rejected", "badge-danger"),
    CANCELLED("Cancelled", "badge-neutral"),
    FORCE_CLOSED("Closed by administrator", "badge-danger");

    private final String label;
    private final String badgeClass;

    ActionType(String label, String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    public String getLabel() { return label; }
    public String getBadgeClass() { return badgeClass; }
}