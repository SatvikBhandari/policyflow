package com.policyflow.policyflow.security;

import com.policyflow.policyflow.enums.Role;

/** Where each role lands after signing in. */
public final class RoleLanding {

    private RoleLanding() {
    }

    public static String urlFor(Role role) {
        return switch (role) {
            case EMPLOYEE -> "/";
            case MANAGER -> "/manager";
            case FINANCE_OFFICER -> "/finance";
            case DEPARTMENT_HEAD -> "/department";
            case ADMIN -> "/admin";
        };
    }
}