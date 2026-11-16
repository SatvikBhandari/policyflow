package com.policyflow.policyflow.enums;

/** The state of one approval stage of a request. */
public enum StageStatus {
    WAITING("Waiting", "badge-neutral"),           // an earlier stage has not been approved yet
    PENDING("Awaiting decision", "badge-warning"), // this stage is the current one
    APPROVED("Approved", "badge-success"),
    REJECTED("Rejected", "badge-danger"),
    CLOSED("Closed", "badge-neutral");             // the request ended before this stage was decided

    private final String label;
    private final String badgeClass;

    StageStatus(String label, String badgeClass) {
        this.label = label;
        this.badgeClass = badgeClass;
    }

    public String getLabel() { return label; }
    public String getBadgeClass() { return badgeClass; }
}