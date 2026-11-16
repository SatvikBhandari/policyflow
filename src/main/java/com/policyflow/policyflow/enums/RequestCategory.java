package com.policyflow.policyflow.enums;

public enum RequestCategory {
    HARDWARE("Hardware"),
    SOFTWARE("Software"),
    TRAVEL("Travel"),
    OFFICE_SUPPLIES("Office Supplies"),
    OTHER("Other");

    private final String label;

    RequestCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}