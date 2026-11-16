package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.CreateUserForm;
import com.policyflow.policyflow.dto.EditUserForm;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.exception.UserAdminException;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;

@SpringBootTest
@Transactional // everything is rolled back, so your real users are untouched
class AdminUserServiceTest {

    @Autowired private AdminUserService service;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuditLogRepository auditLogRepository;

    private Department department;
    private Department otherDepartment;
    private User admin;
    private User employee;

    @BeforeEach
    void setUp() {
        department = departmentRepository.save(new Department("Admin Test", "ADMTST"));
        otherDepartment = departmentRepository.save(new Department("Admin Other", "ADMOTH"));
        admin = userRepository.save(new User("Test Admin", "adm-admin@policyflow.local", Role.ADMIN, department));
        employee = userRepository.save(new User("Test Employee", "adm-employee@policyflow.local", Role.EMPLOYEE, department));
    }

    // ---- helpers ----

    private CreateUserForm createForm(String name, String email, Role role, Department dept, String password) {
        CreateUserForm form = new CreateUserForm();
        form.setFullName(name);
        form.setEmail(email);
        form.setRole(role);
        form.setDepartmentId(dept.getId());
        form.setTemporaryPassword(password);
        return form;
    }

    private EditUserForm editForm(String name, Role role, Department dept) {
        EditUserForm form = new EditUserForm();
        form.setFullName(name);
        form.setRole(role);
        form.setDepartmentId(dept.getId());
        return form;
    }

    private List<String> auditActions(User user) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_USER, user.getId())
                .stream().map(AuditLog::getAction).toList();
    }

    // ---- creating ----

    @Test
    void createStoresATrimmedLowercaseEmailAHashedPasswordAndTheForceChangeFlag() {
        User created = service.create(admin, createForm("  New Person  ", "  New.Person@PolicyFlow.Local  ",
                Role.MANAGER, department, "Temp@12345"));

        assertEquals("New Person", created.getFullName());
        assertEquals("new.person@policyflow.local", created.getEmail());
        assertEquals(Role.MANAGER, created.getRole());
        assertTrue(created.isActive());
        assertTrue(created.isMustChangePassword());
        assertNotEquals("Temp@12345", created.getPasswordHash());
        assertTrue(created.getPasswordHash().startsWith("$2"), "expected a BCrypt hash");
        assertTrue(passwordEncoder.matches("Temp@12345", created.getPasswordHash()));

        assertEquals(List.of(AuditService.USER_CREATED), auditActions(created));
        AuditLog entry = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_USER, created.getId()).get(0);
        assertEquals(admin.getId(), entry.getActorId());
        assertFalse(entry.getDetails().contains("Temp@12345"), "the audit log must never contain a password");
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() {
        service.create(admin, createForm("First", "dup@policyflow.local", Role.EMPLOYEE, department, "Temp@12345"));

        UserAdminException ex = assertThrows(UserAdminException.class, () ->
                service.create(admin, createForm("Second", "DUP@policyflow.local", Role.EMPLOYEE, department, "Temp@12345")));

        assertEquals("email", ex.getField());
    }

    @Test
    void weakTemporaryPasswordIsRejectedAndNothingIsSaved() {
        UserAdminException ex = assertThrows(UserAdminException.class, () ->
                service.create(admin, createForm("Weak", "weak@policyflow.local", Role.EMPLOYEE, department, "short")));

        assertEquals("temporaryPassword", ex.getField());
        assertFalse(userRepository.existsByEmail("weak@policyflow.local"));
    }

    @Test
    void inactiveDepartmentIsRejected() {
        otherDepartment.setActive(false);
        departmentRepository.saveAndFlush(otherDepartment);

        UserAdminException ex = assertThrows(UserAdminException.class, () ->
                service.create(admin, createForm("Nobody", "nobody@policyflow.local", Role.EMPLOYEE,
                        otherDepartment, "Temp@12345")));

        assertEquals("departmentId", ex.getField());
    }

    @Test
    void onlyAnActiveAdministratorCanUseTheService() {
        CreateUserForm form = createForm("Sneaky", "sneaky@policyflow.local", Role.ADMIN, department, "Temp@12345");

        assertThrows(AccessDeniedException.class, () -> service.create(employee, form));

        admin.setActive(false);
        userRepository.saveAndFlush(admin);
        assertThrows(AccessDeniedException.class, () -> service.create(admin, form));
        assertFalse(userRepository.existsByEmail("sneaky@policyflow.local"));
    }

    // ---- updating ----

    @Test
    void updateChangesRoleAndDepartmentAndIsAudited() {
        User updated = service.update(admin, employee.getId(), editForm("Renamed Employee", Role.MANAGER, otherDepartment));

        assertEquals("Renamed Employee", updated.getFullName());
        assertEquals(Role.MANAGER, updated.getRole());
        assertEquals(otherDepartment.getId(), updated.getDepartment().getId());

        assertEquals(List.of(AuditService.USER_UPDATED), auditActions(employee));
        String details = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_USER, employee.getId()).get(0).getDetails();
        assertTrue(details.contains("Manager"));
        assertTrue(details.contains("Admin Other"));
    }

    @Test
    void anAdministratorCannotChangeTheirOwnRole() {
        UserAdminException ex = assertThrows(UserAdminException.class, () ->
                service.update(admin, admin.getId(), editForm("Test Admin", Role.EMPLOYEE, department)));

        assertEquals("role", ex.getField());
        assertEquals(Role.ADMIN, userRepository.findById(admin.getId()).orElseThrow().getRole());
    }

    @Test
    void updateWithoutChangesWritesNoAuditEntry() {
        service.update(admin, employee.getId(), editForm("Test Employee", Role.EMPLOYEE, department));

        assertTrue(auditActions(employee).isEmpty());
    }

    // ---- activating, deactivating, resetting ----

    @Test
    void deactivatingAndReactivatingAreAudited() {
        service.setActive(admin, employee.getId(), false);
        assertFalse(userRepository.findById(employee.getId()).orElseThrow().isActive());

        service.setActive(admin, employee.getId(), true);
        assertTrue(userRepository.findById(employee.getId()).orElseThrow().isActive());

        assertEquals(List.of(AuditService.USER_DEACTIVATED, AuditService.USER_ACTIVATED), auditActions(employee));
    }

    @Test
    void anAdministratorCannotDeactivateTheirOwnAccount() {
        assertThrows(UserAdminException.class, () -> service.setActive(admin, admin.getId(), false));

        assertTrue(userRepository.findById(admin.getId()).orElseThrow().isActive());
    }

    @Test
    void resetPasswordSetsANewHashAndForcesAChange() {
        employee.setPasswordHash(passwordEncoder.encode("Old@12345"));
        userRepository.saveAndFlush(employee);

        service.resetPassword(admin, employee.getId(), "Temp@12345");

        User reloaded = userRepository.findById(employee.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("Temp@12345", reloaded.getPasswordHash()));
        assertFalse(passwordEncoder.matches("Old@12345", reloaded.getPasswordHash()));
        assertTrue(reloaded.isMustChangePassword());
        assertEquals(List.of(AuditService.USER_PASSWORD_RESET), auditActions(employee));
    }

    @Test
    void anAdministratorCannotResetTheirOwnPassword() {
        assertThrows(UserAdminException.class, () -> service.resetPassword(admin, admin.getId(), "Temp@12345"));
    }

    // ---- searching ----

    @Test
    void searchFiltersByTextRoleAndStatus() {
        User activeEmployee = userRepository.save(
                new User("Zzqx Active", "zzqx.active@policyflow.local", Role.EMPLOYEE, department));
        User inactiveManager = new User("Zzqx Inactive", "zzqx.inactive@policyflow.local", Role.MANAGER, department);
        inactiveManager.setActive(false);
        inactiveManager = userRepository.save(inactiveManager);

        assertEquals(2, service.search(admin, "zzqx", null, null).size());
        assertEquals(2, service.search(admin, "ZZQX.", null, null).size()); // matches the email, ignoring case

        List<User> managers = service.search(admin, "zzqx", Role.MANAGER, null);
        assertEquals(1, managers.size());
        assertEquals(inactiveManager.getId(), managers.get(0).getId());

        List<User> active = service.search(admin, "zzqx", null, true);
        assertEquals(1, active.size());
        assertEquals(activeEmployee.getId(), active.get(0).getId());

        assertEquals(1, service.search(admin, "zzqx", null, false).size());
        assertTrue(service.search(admin, "zzqx", Role.FINANCE_OFFICER, null).isEmpty());
    }

    @Test
    void unknownUserIsReportedAsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> service.get(admin, 999_999_999L));
    }
}