package com.policyflow.policyflow.service;

import java.util.Objects;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.exception.UserAdminException;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.PasswordPolicy;

/** Lets a user change their own password. */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AccountService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public void changePassword(User current, String currentPassword, String newPassword, String confirmPassword) {
        User user = userRepository.findById(current.getId())
                .orElseThrow(() -> new IllegalStateException("Account no longer exists"));

        if (currentPassword == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new UserAdminException("currentPassword", "Current password is incorrect.");
        }
        if (!Objects.equals(newPassword, confirmPassword)) {
            throw new UserAdminException("confirmPassword", "The new passwords do not match.");
        }
        String problem = PasswordPolicy.check(newPassword);
        if (problem != null) {
            throw new UserAdminException("newPassword", problem);
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new UserAdminException("newPassword", "Choose a password different from the current one.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        auditService.record(user, AuditService.PASSWORD_CHANGED, AuditService.ENTITY_USER, user.getId(),
                "Password changed by the account owner");
    }
}