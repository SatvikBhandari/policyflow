package com.policyflow.policyflow.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.CreateUserForm;
import com.policyflow.policyflow.dto.EditUserForm;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.exception.UserAdminException;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.PasswordPolicy;

/** User administration. Only an active administrator may use it; every change is audited. */
@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AdminUserService(UserRepository userRepository,
                            DepartmentRepository departmentRepository,
                            PasswordEncoder passwordEncoder,
                            AuditService auditService) {
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<User> search(User admin, String query, Role role, Boolean active) {
        requireAdmin(admin);
        return filter(userRepository.findAllWithDepartment(), query, role, active);
    }

    @Transactional(readOnly = true)
    public User get(User admin, Long userId) {
        requireAdmin(admin);
        return load(userId);
    }

    @Transactional(readOnly = true)
    public List<Department> activeDepartments() {
        return departmentRepository.findAll().stream()
                .filter(Department::isActive)
                .sorted(Comparator.comparing(Department::getName))
                .toList();
    }

    /** Text matches the name or email (ignoring case); role and status are optional filters. */
    static List<User> filter(List<User> users, String query, Role role, Boolean active) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return users.stream()
                .filter(u -> needle.isEmpty()
                        || u.getFullName().toLowerCase(Locale.ROOT).contains(needle)
                        || u.getEmail().toLowerCase(Locale.ROOT).contains(needle))
                .filter(u -> role == null || u.getRole() == role)
                .filter(u -> active == null || u.isActive() == active)
                .toList();
    }

    // ------------------------------------------------------------------ changes

    /** Creates an account with a temporary password that must be changed at first login. */
    @Transactional
    public User create(User admin, CreateUserForm form) {
        User actor = requireAdmin(admin);

        String email = form.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new UserAdminException("email", "A user with this email already exists.");
        }
        String problem = PasswordPolicy.check(form.getTemporaryPassword());
        if (problem != null) {
            throw new UserAdminException("temporaryPassword", problem);
        }
        Department department = activeDepartment(form.getDepartmentId());

        User user = new User(form.getFullName().trim(), email, form.getRole(), department);
        user.setPasswordHash(passwordEncoder.encode(form.getTemporaryPassword()));
        user.setMustChangePassword(true);
        user = userRepository.save(user);

        audit(actor, AuditService.USER_CREATED, user,
                "Created " + email + " as " + user.getRole().getLabel() + " in " + department.getName());
        return user;
    }

    /** Changes name, role and department. Administrators cannot change their own role. */
    @Transactional
    public User update(User admin, Long userId, EditUserForm form) {
        User actor = requireAdmin(admin);
        User target = load(userId);

        if (isSelf(actor, target) && form.getRole() != target.getRole()) {
            throw new UserAdminException("role", "You cannot change your own role.");
        }

        Department newDepartment = Objects.equals(form.getDepartmentId(), target.getDepartment().getId())
                ? target.getDepartment()
                : activeDepartment(form.getDepartmentId());

        List<String> changes = new ArrayList<>();
        String newName = form.getFullName().trim();
        if (!newName.equals(target.getFullName())) {
            changes.add("name: " + target.getFullName() + " -> " + newName);
            target.setFullName(newName);
        }
        if (form.getRole() != target.getRole()) {
            changes.add("role: " + target.getRole().getLabel() + " -> " + form.getRole().getLabel());
            target.setRole(form.getRole());
        }
        if (!Objects.equals(newDepartment.getId(), target.getDepartment().getId())) {
            changes.add("department: " + target.getDepartment().getName() + " -> " + newDepartment.getName());
            target.setDepartment(newDepartment);
        }

        if (changes.isEmpty()) {
            return target; // nothing changed, so nothing to save or audit
        }
        userRepository.save(target);
        audit(actor, AuditService.USER_UPDATED, target,
                target.getEmail() + " updated (" + String.join("; ", changes) + ")");
        return target;
    }

    /** Activates or deactivates an account. Administrators cannot deactivate themselves. */
    @Transactional
    public User setActive(User admin, Long userId, boolean active) {
        User actor = requireAdmin(admin);
        User target = load(userId);

        if (isSelf(actor, target) && !active) {
            throw new UserAdminException("You cannot deactivate your own account.");
        }
        if (target.isActive() == active) {
            return target;
        }
        target.setActive(active);
        userRepository.save(target);
        audit(actor, active ? AuditService.USER_ACTIVATED : AuditService.USER_DEACTIVATED, target,
                target.getEmail() + (active ? " activated" : " deactivated"));
        return target;
    }

    /** Sets a new temporary password; the user must change it at next login. */
    @Transactional
    public void resetPassword(User admin, Long userId, String temporaryPassword) {
        User actor = requireAdmin(admin);
        User target = load(userId);

        if (isSelf(actor, target)) {
            throw new UserAdminException("Use \"Change password\" in your profile menu to change your own password.");
        }
        String problem = PasswordPolicy.check(temporaryPassword);
        if (problem != null) {
            throw new UserAdminException("temporaryPassword", problem);
        }
        target.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        target.setMustChangePassword(true);
        userRepository.save(target);
        audit(actor, AuditService.USER_PASSWORD_RESET, target,
                "Temporary password set for " + target.getEmail());
    }

    // ------------------------------------------------------------------ helpers

    /** Loads the caller from the database and requires an active administrator. */
    private User requireAdmin(User admin) {
        Objects.requireNonNull(admin, "admin must not be null");
        User actor = userRepository.findById(admin.getId())
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));
        if (!actor.isActive() || actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Administrator access required");
        }
        return actor;
    }

    private User load(Long userId) {
        return userRepository.findWithDepartmentById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private Department activeDepartment(Long departmentId) {
        return departmentRepository.findById(departmentId)
                .filter(Department::isActive)
                .orElseThrow(() -> new UserAdminException("departmentId", "Choose an active department."));
    }

    private static boolean isSelf(User actor, User target) {
        return Objects.equals(actor.getId(), target.getId());
    }

    private void audit(User actor, String action, User target, String details) {
        auditService.record(actor, action, AuditService.ENTITY_USER, target.getId(), details);
    }
}