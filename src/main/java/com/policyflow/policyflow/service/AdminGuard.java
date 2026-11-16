package com.policyflow.policyflow.service;

import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.UserRepository;

/** Loads the caller from the database and requires an active administrator. */
@Component
public class AdminGuard {

    private final UserRepository userRepository;

    public AdminGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User requireAdmin(User admin) {
        Objects.requireNonNull(admin, "admin must not be null");
        User actor = userRepository.findById(admin.getId())
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));
        if (!actor.isActive() || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Administrator access required");
        }
        return actor;
    }
}