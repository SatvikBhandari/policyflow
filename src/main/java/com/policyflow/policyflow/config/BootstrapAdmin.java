package com.policyflow.policyflow.config;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.PasswordPolicy;
import com.policyflow.policyflow.service.AuditService;

/**
 * Creates the first administrator in production, from environment variables,
 * but only when no active administrator exists. The account must change its password at first sign in.
 */
@Component
@Profile("prod")
public class BootstrapAdmin {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    private static final String HEAD_OFFICE_CODE = "HQ";

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final String email;
    private final String password;
    private final String displayName;

    public BootstrapAdmin(UserRepository userRepository,
                          DepartmentRepository departmentRepository,
                          PasswordEncoder passwordEncoder,
                          AuditService auditService,
                          @Value("${policyflow.bootstrap.admin-email:}") String email,
                          @Value("${policyflow.bootstrap.admin-password:}") String password,
                          @Value("${policyflow.bootstrap.admin-name:Administrator}") String displayName) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.email = email;
        this.password = password;
        this.displayName = displayName;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void createInitialAdminIfNeeded() {
        if (userRepository.countByRoleAndActiveTrue(Role.ADMIN) > 0) {
            return; // an administrator already exists
        }

        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty() || password == null || password.isBlank()) {
            log.warn("No active administrator exists and no bootstrap credentials are configured. "
                    + "Set POLICYFLOW_ADMIN_EMAIL and POLICYFLOW_ADMIN_PASSWORD, then restart.");
            return;
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.warn("The configured bootstrap email belongs to an existing account, so no administrator was created. "
                    + "Activate or promote that account from the database instead.");
            return;
        }
        String problem = PasswordPolicy.check(password);
        if (problem != null) {
            throw new IllegalStateException("POLICYFLOW_ADMIN_PASSWORD is not acceptable: " + problem);
        }

        Department headOffice = departmentRepository.findByCode(HEAD_OFFICE_CODE)
                .orElseGet(() -> departmentRepository.save(new Department("Head Office", HEAD_OFFICE_CODE)));

        String name = displayName == null || displayName.isBlank() ? "Administrator" : displayName.trim();
        User admin = new User(name, normalizedEmail, Role.ADMIN, headOffice);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setMustChangePassword(true);
        admin = userRepository.save(admin);

        auditService.record(admin, AuditService.USER_CREATED, AuditService.ENTITY_USER, admin.getId(),
                "Initial administrator " + normalizedEmail + " created at startup");
        log.info("Initial administrator created: {}", normalizedEmail);
    }
}