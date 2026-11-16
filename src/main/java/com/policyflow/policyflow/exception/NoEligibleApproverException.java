package com.policyflow.policyflow.exception;

import com.policyflow.policyflow.enums.Role;

/** Thrown when a stage of the approval chain has nobody who is allowed to decide it. */
public class NoEligibleApproverException extends RuntimeException {

    private final Role role;

    public NoEligibleApproverException(Role role) {
        super("No eligible " + role.getLabel() + " is available for this request, "
                + "so it cannot be submitted yet. Please contact your administrator.");
        this.role = role;
    }

    public Role getRole() {
        return role;
    }
}