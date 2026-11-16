package com.policyflow.policyflow.exception;

/** A policy-administration rule was broken. The message is safe to show to the user. */
public class PolicyAdminException extends RuntimeException {

    private final String field;

    public PolicyAdminException(String message) {
        this(null, message);
    }

    public PolicyAdminException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The form field the problem belongs to, or null when it is not tied to one field. */
    public String getField() {
        return field;
    }
}