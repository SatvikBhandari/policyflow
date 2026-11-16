package com.policyflow.policyflow.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.service.AuditService;

@SpringBootTest
@Transactional // everything is rolled back, so your real administrators are restored
class BootstrapAdminTest {

    private static final String EMAIL = "bootstrap-admin@policyflow.local";
    private static final String PASSWORD = "Start@12345";

    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuditService auditService;
    @Autowired private AuditLogRepository auditLogRepository;

    private Department department;

    @BeforeEach
    void noActiveAdministrators() {
        List<User> admins = userRepository.findAll().stream().filter(u -> u.getRole() == Role.ADMIN).toList();
        admins.forEach(u -> u.setActive(false));
        userRepository.saveAllAndFlush(admins);
        department = departmentRepository.save(new Department("Bootstrap Test", "BSTEST"));
    }

    private BootstrapAdmin bootstrap(String email, String password, String name) {
        return new BootstrapAdmin(userRepository, departmentRepository, passwordEncoder, auditService,
                email, password, name);
    }

    @Test
    void createsAnAdministratorWithAHashedTemporaryPassword() {
        bootstrap(" Bootstrap-Admin@PolicyFlow.Local ", PASSWORD, "First Admin").createInitialAdminIfNeeded();

        User admin = userRepository.findByEmail(EMAIL).orElseThrow();
        assertEquals(Role.ADMIN, admin.getRole());
        assertEquals("First Admin", admin.getFullName());
        assertTrue(admin.isActive());
        assertTrue(admin.isMustChangePassword());
        assertTrue(admin.getPasswordHash().startsWith("$2"), "expected a BCrypt hash");
        assertTrue(passwordEncoder.matches(PASSWORD, admin.getPasswordHash()));

        List<AuditLog> audit = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_USER, admin.getId());
        assertEquals(List.of(AuditService.USER_CREATED), audit.stream().map(AuditLog::getAction).toList());
        assertFalse(audit.get(0).getDetails().contains(PASSWORD), "the audit log must never contain a password");
    }

    @Test
    void doesNothingWhenAnActiveAdministratorExists() {
        userRepository.save(new User("Existing Admin", "existing-admin@policyflow.local", Role.ADMIN, department));

        bootstrap(EMAIL, PASSWORD, "Second Admin").createInitialAdminIfNeeded();

        assertTrue(userRepository.findByEmail(EMAIL).isEmpty());
    }

    @Test
    void doesNothingWithoutCredentials() {
        bootstrap("", PASSWORD, "Nobody").createInitialAdminIfNeeded();
        bootstrap(EMAIL, "", "Nobody").createInitialAdminIfNeeded();
        bootstrap(null, null, null).createInitialAdminIfNeeded();

        assertTrue(userRepository.findByEmail(EMAIL).isEmpty());
    }

    @Test
    void refusesAWeakPasswordAndCreatesNothing() {
        BootstrapAdmin weak = bootstrap(EMAIL, "short", "Weak Admin");

        assertThrows(IllegalStateException.class, weak::createInitialAdminIfNeeded);
        assertTrue(userRepository.findByEmail(EMAIL).isEmpty());
    }

    @Test
    void neverTouchesAnExistingAccountWithTheSameEmail() {
        userRepository.save(new User("Existing Employee", EMAIL, Role.EMPLOYEE, department));

        bootstrap(EMAIL, PASSWORD, "Impostor").createInitialAdminIfNeeded();

        User unchanged = userRepository.findByEmail(EMAIL).orElseThrow();
        assertEquals(Role.EMPLOYEE, unchanged.getRole());
        assertEquals("Existing Employee", unchanged.getFullName());
        assertEquals(null, unchanged.getPasswordHash());
    }
}