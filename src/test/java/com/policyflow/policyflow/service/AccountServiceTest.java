package com.policyflow.policyflow.service;

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
import com.policyflow.policyflow.exception.UserAdminException;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;

@SpringBootTest
@Transactional // everything is rolled back, so your real users are untouched
class AccountServiceTest {

    private static final String OLD = "Old@12345";

    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuditLogRepository auditLogRepository;

    private User user;

    @BeforeEach
    void setUp() {
        Department department = departmentRepository.save(new Department("Account Test", "ACCTST"));
        user = new User("Account Tester", "acc-tester@policyflow.local", Role.EMPLOYEE, department);
        user.setPasswordHash(passwordEncoder.encode(OLD));
        user.setMustChangePassword(true);
        user = userRepository.save(user);
    }

    private User reload() {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    @Test
    void changingThePasswordStoresTheNewHashClearsTheFlagAndIsAudited() {
        accountService.changePassword(user, OLD, "Fresh@12345", "Fresh@12345");

        User reloaded = reload();
        assertTrue(passwordEncoder.matches("Fresh@12345", reloaded.getPasswordHash()));
        assertFalse(passwordEncoder.matches(OLD, reloaded.getPasswordHash()));
        assertFalse(reloaded.isMustChangePassword());

        List<String> actions = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_USER, user.getId())
                .stream().map(AuditLog::getAction).toList();
        assertEquals(List.of(AuditService.PASSWORD_CHANGED), actions);
    }

    @Test
    void wrongCurrentPasswordIsRefusedAndNothingChanges() {
        UserAdminException ex = assertThrows(UserAdminException.class,
                () -> accountService.changePassword(user, "Wrong@12345", "Fresh@12345", "Fresh@12345"));

        assertEquals("currentPassword", ex.getField());
        assertTrue(passwordEncoder.matches(OLD, reload().getPasswordHash()));
        assertTrue(reload().isMustChangePassword());
    }

    @Test
    void missingCurrentPasswordIsRefused() {
        UserAdminException ex = assertThrows(UserAdminException.class,
                () -> accountService.changePassword(user, null, "Fresh@12345", "Fresh@12345"));

        assertEquals("currentPassword", ex.getField());
    }

    @Test
    void mismatchedConfirmationIsRefused() {
        UserAdminException ex = assertThrows(UserAdminException.class,
                () -> accountService.changePassword(user, OLD, "Fresh@12345", "Different@12345"));

        assertEquals("confirmPassword", ex.getField());
        assertTrue(passwordEncoder.matches(OLD, reload().getPasswordHash()));
    }

    @Test
    void weakNewPasswordIsRefused() {
        UserAdminException ex = assertThrows(UserAdminException.class,
                () -> accountService.changePassword(user, OLD, "weak", "weak"));

        assertEquals("newPassword", ex.getField());
        assertTrue(passwordEncoder.matches(OLD, reload().getPasswordHash()));
    }

    @Test
    void reusingTheCurrentPasswordIsRefused() {
        UserAdminException ex = assertThrows(UserAdminException.class,
                () -> accountService.changePassword(user, OLD, OLD, OLD));

        assertEquals("newPassword", ex.getField());
        assertTrue(reload().isMustChangePassword());
    }
}