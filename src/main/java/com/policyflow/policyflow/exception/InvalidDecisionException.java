package com.policyflow.policyflow.exception;

/** Thrown when a decision cannot be recorded (missing comment, no longer awaiting this person, and so on). */
public class InvalidDecisionException extends RuntimeException {

    public InvalidDecisionException(String message) {
        super(message);
    }
}