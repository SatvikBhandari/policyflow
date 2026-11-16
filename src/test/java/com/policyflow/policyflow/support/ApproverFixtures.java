package com.policyflow.policyflow.support;

import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.UserRepository;

/** Test helper: gives a test department a Manager, a Finance Officer and a Department Head. */
public final class ApproverFixtures {

    private ApproverFixtures() {
    }

    public static void createApprovers(UserRepository userRepository, Department department, String emailPrefix) {
        for (Role role : new Role[] {Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD}) {
            userRepository.save(new User(
                    role.getLabel() + " (" + emailPrefix + ")",
                    emailPrefix + "-" + role.name().toLowerCase() + "@policyflow.local",
                    role, department));
        }
    }
}