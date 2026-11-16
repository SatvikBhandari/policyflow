package com.policyflow.policyflow.enums;

public enum RequestPriority {
    LOW("Low", "badge-neutral"),
    NORMAL("Normal", "badge-info"),
    URGENT("Urgent", "badge-danger");

    private final String label;
    private final String badgeClass;

    RequestPriority(String label, String badgeClass) {
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