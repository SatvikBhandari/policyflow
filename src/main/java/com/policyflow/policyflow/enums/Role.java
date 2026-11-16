package com.policyflow.policyflow.enums;

public enum Role {
    EMPLOYEE("Employee"),
    MANAGER("Manager"),
    FINANCE_OFFICER("Finance Officer"),
    DEPARTMENT_HEAD("Department Head"),
    ADMIN("Administrator");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Roles that can sit in an approval chain. The order in which the constants are
     * declared (MANAGER, FINANCE_OFFICER, DEPARTMENT_HEAD) is the order of approval.
     */
    public boolean isApprover() {
        return this == MANAGER || this == FINANCE_OFFICER || this == DEPARTMENT_HEAD;
    }
}