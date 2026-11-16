package com.policyflow.policyflow.exception;

/** A user-administration or password rule was broken. The message is safe to show to the user. */
public class UserAdminException extends RuntimeException {

    private final String field;

    public UserAdminException(String message) {
        this(null, message);
    }

    public UserAdminException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The form field the problem belongs to, or null when it is not tied to one field. */
    public String getField() {
        return field;
    }
}