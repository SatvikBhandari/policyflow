package com.policyflow.policyflow.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.PolicyCoverage;
import com.policyflow.policyflow.dto.PolicyCoverage.Gap;
import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.dto.PolicyForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.PolicyAdminException;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;

/** Policy administration. Only an active administrator may use it; every change is audited. */
@Service
public class PolicyAdminService {

    private static final BigDecimal SMALLEST_AMOUNT = new BigDecimal("0.01");
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final ApprovalPolicyRepository policyRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final PolicyEvaluationService evaluationService;
    private final AuditService auditService;

    public PolicyAdminService(ApprovalPolicyRepository policyRepository,
                              DepartmentRepository departmentRepository,
                              UserRepository userRepository,
                              PolicyEvaluationService evaluationService,
                              AuditService auditService) {
        this.policyRepository = policyRepository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
        this.evaluationService = evaluationService;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<ApprovalPolicy> list(User admin) {
        requireAdmin(admin);
        return policyRepository.findAllWithDepartment();
    }

    @Transactional(readOnly = true)
    public ApprovalPolicy get(User admin, Long policyId) {
        requireAdmin(admin);
        return load(policyId);
    }

    @Transactional(readOnly = true)
    public List<Department> activeDepartments() {
        return departmentRepository.findAll().stream()
                .filter(Department::isActive)
                .sorted(Comparator.comparing(Department::getName))
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyCoverage coverage(User admin) {
        requireAdmin(admin);
        return coverageOf(policyRepository.findAll());
    }

    /**
     * Finds the amounts that no active general policy covers. A "general" policy has no category
     * or department condition and at least one approver. Overlaps are fine (policies combine).
     */
    static PolicyCoverage coverageOf(List<ApprovalPolicy> policies) {
        List<ApprovalPolicy> general = policies.stream()
                .filter(ApprovalPolicy::isActive)
                .filter(p -> p.getCategory() == null && p.getPriority() == null && p.getDepartment() == null)
                .filter(p -> p.getApproverRoles().stream().anyMatch(Role::isApprover))
                .sorted(Comparator.comparing(ApprovalPolicy::getMinAmount))
                .toList();

        if (general.isEmpty()) {
            return new PolicyCoverage(false, List.of());
        }

        List<Gap> gaps = new ArrayList<>();
        BigDecimal cursor = SMALLEST_AMOUNT; // the smallest amount a request can have
        boolean coveredToInfinity = false;
        for (ApprovalPolicy policy : general) {
            if (policy.getMinAmount().compareTo(cursor) > 0) {
                gaps.add(new Gap(cursor, policy.getMinAmount()));
            }
            if (policy.getMaxAmount() == null) {
                coveredToInfinity = true;
                break;
            }
            if (policy.getMaxAmount().compareTo(cursor) > 0) {
                cursor = policy.getMaxAmount();
            }
        }
        if (!coveredToInfinity) {
            gaps.add(new Gap(cursor, null));
        }
        return new PolicyCoverage(true, gaps);
    }

    /** Who would have to approve a request like this? Uses the real engine, so it matches a real submission. */
    @Transactional(readOnly = true)
    public PolicyEvaluation preview(User admin, BigDecimal amount, RequestCategory category, Long departmentId) {
        return preview(admin, amount, category, RequestPriority.NORMAL, departmentId);
    }

    /** Who would have to approve a request with this priority? Uses the real engine. */
    @Transactional(readOnly = true)
    public PolicyEvaluation preview(User admin, BigDecimal amount, RequestCategory category,
                                    RequestPriority priority, Long departmentId) {
        requireAdmin(admin);
        if (amount == null || amount.compareTo(SMALLEST_AMOUNT) < 0) {
            throw new PolicyAdminException("amount", "Enter an amount of at least 0.01.");
        }
        if (category == null) {
            throw new PolicyAdminException("category", "Choose a category.");
        }
        Department department = null;
        if (departmentId != null) {
            department = departmentRepository.findById(departmentId)
                    .orElseThrow(() -> new PolicyAdminException("department", "Unknown department."));
        }
        return evaluationService.evaluate(amount, category,
                priority == null ? RequestPriority.NORMAL : priority, department);
    }

    // ------------------------------------------------------------------ changes

    @Transactional
    public ApprovalPolicy create(User admin, PolicyForm form) {
        User actor = requireAdmin(admin);
        String name = validate(form, null);
        Department department = resolveDepartment(form.getDepartmentId(), null);

        ApprovalPolicy policy = new ApprovalPolicy(name, form.getMinAmount(), form.getMaxAmount(),
                form.getCategory(), department);
        policy.setDescription(cleanDescription(form.getDescription()));
        policy.setPriority(form.getPriority());
        policy.replaceApprovers(form.getApproverRoles());
        policy.setActive(form.isActive());
        policy = policyRepository.saveAndFlush(policy);

        audit(actor, AuditService.POLICY_CREATED, policy,
                "Created policy \"" + name + "\" (" + describe(policy) + ")");
        return policy;
    }

    /**
     * Edits a policy in place. The form must carry the version it was opened with: if the policy
     * changed in the meantime, nothing is saved. Requests already in progress keep their own stage snapshot.
     */
    @Transactional
    public ApprovalPolicy update(User admin, Long policyId, PolicyForm form) {
        User actor = requireAdmin(admin);
        ApprovalPolicy policy = load(policyId);
        requireCurrentVersion(policy, form.getVersion());

        String name = validate(form, policyId);
        Department department = resolveDepartment(form.getDepartmentId(), policy.getDepartment());
        String description = cleanDescription(form.getDescription());

        List<String> changes = new ArrayList<>();
        if (!name.equals(policy.getName())) {
            changes.add("name: " + policy.getName() + " -> " + name);
        }
        if (!Objects.equals(description, policy.getDescription())) {
            changes.add("description changed");
        }
        boolean rangeChanged = form.getMinAmount().compareTo(policy.getMinAmount()) != 0
                || !sameAmount(form.getMaxAmount(), policy.getMaxAmount());
        if (rangeChanged) {
            changes.add("amount range: " + range(policy.getMinAmount(), policy.getMaxAmount())
                    + " -> " + range(form.getMinAmount(), form.getMaxAmount()));
        }
        if (form.getCategory() != policy.getCategory()) {
            changes.add("category: " + categoryLabel(policy.getCategory()) + " -> " + categoryLabel(form.getCategory()));
        }
        if (form.getPriority() != policy.getPriority()) {
            changes.add("priority: " + priorityLabel(policy.getPriority()) + " -> " + priorityLabel(form.getPriority()));
        }
        Long oldDepartmentId = policy.getDepartment() == null ? null : policy.getDepartment().getId();
        Long newDepartmentId = department == null ? null : department.getId();
        if (!Objects.equals(oldDepartmentId, newDepartmentId)) {
            changes.add("department: " + departmentName(policy.getDepartment()) + " -> " + departmentName(department));
        }
        Set<Role> newRoles = EnumSet.noneOf(Role.class);
        newRoles.addAll(form.getApproverRoles());
        boolean approversChanged = !newRoles.equals(new HashSet<>(policy.getApproverRoles()));
        if (approversChanged) {
            changes.add("approvers: " + labels(policy.getApproverRolesInOrder()) + " -> " + labels(newRoles));
        }

        if (changes.isEmpty()) {
            return policy; // nothing changed, so nothing to save or audit
        }

        policy.update(name, description, form.getMinAmount(), form.getMaxAmount(), form.getCategory(), department);
        policy.setPriority(form.getPriority());
        if (approversChanged) {
            policy.replaceApprovers(newRoles);
        }
        policy = policyRepository.saveAndFlush(policy); // the version check against concurrent commits happens here

        audit(actor, AuditService.POLICY_UPDATED, policy,
                "Updated policy \"" + name + "\" (" + String.join("; ", changes) + ")");
        return policy;
    }

    /** Enables or disables a policy. Disabled policies are ignored for new requests. */
    @Transactional
    public ApprovalPolicy setActive(User admin, Long policyId, boolean active, Long expectedVersion) {
        User actor = requireAdmin(admin);
        ApprovalPolicy policy = load(policyId);
        requireCurrentVersion(policy, expectedVersion);

        if (policy.isActive() == active) {
            return policy;
        }
        policy.setActive(active);
        policy = policyRepository.saveAndFlush(policy);
        audit(actor, active ? AuditService.POLICY_ACTIVATED : AuditService.POLICY_DEACTIVATED, policy,
                "Policy \"" + policy.getName() + "\" " + (active ? "enabled" : "disabled"));
        return policy;
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

    private ApprovalPolicy load(Long policyId) {
        return policyRepository.findWithDepartmentById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy not found"));
    }

    private static void requireCurrentVersion(ApprovalPolicy policy, Long expectedVersion) {
        if (expectedVersion == null || !expectedVersion.equals(policy.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(ApprovalPolicy.class, policy.getId());
        }
    }

    /** Checks everything the form cannot check on its own. Returns the trimmed name. */
    private String validate(PolicyForm form, Long existingId) {
        Objects.requireNonNull(form, "form must not be null");

        String name = form.getName() == null ? "" : form.getName().trim();
        if (name.isEmpty()) {
            throw new PolicyAdminException("name", "Policy name is required.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new PolicyAdminException("name", "Policy name must be at most " + MAX_NAME_LENGTH + " characters.");
        }
        policyRepository.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(existingId))
                .ifPresent(other -> {
                    throw new PolicyAdminException("name", "A policy with this name already exists.");
                });

        BigDecimal min = form.getMinAmount();
        if (min == null) {
            throw new PolicyAdminException("minAmount", "Minimum amount is required.");
        }
        if (min.signum() < 0) {
            throw new PolicyAdminException("minAmount", "Minimum amount cannot be negative.");
        }
        requireMoney(min, "minAmount");

        BigDecimal max = form.getMaxAmount();
        if (max != null) {
            requireMoney(max, "maxAmount");
            if (max.compareTo(min) <= 0) {
                throw new PolicyAdminException("maxAmount", "Maximum amount must be greater than the minimum amount.");
            }
        }

        Collection<Role> roles = form.getApproverRoles();
        if (roles == null || roles.isEmpty()) {
            throw new PolicyAdminException("approverRoles", "Choose at least one approver.");
        }
        for (Role role : roles) {
            if (role == null || !role.isApprover()) {
                throw new PolicyAdminException("approverRoles",
                        "Only Manager, Finance Officer and Department Head can approve requests.");
            }
        }

        cleanDescription(form.getDescription()); // validates the length
        return name;
    }

    private static void requireMoney(BigDecimal value, String field) {
        BigDecimal stripped = value.stripTrailingZeros();
        int integerDigits = stripped.precision() - stripped.scale();
        if (stripped.scale() > 2 || integerDigits > 13) {
            throw new PolicyAdminException(field, "Use at most 13 digits and 2 decimal places.");
        }
    }

    private static String cleanDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new PolicyAdminException("description",
                    "Description must be at most " + MAX_DESCRIPTION_LENGTH + " characters.");
        }
        return trimmed;
    }

    /** A department must be active, unless the policy already uses it. Null means "any department". */
    private Department resolveDepartment(Long departmentId, Department current) {
        if (departmentId == null) {
            return null;
        }
        if (current != null && departmentId.equals(current.getId())) {
            return current;
        }
        return departmentRepository.findById(departmentId)
                .filter(Department::isActive)
                .orElseThrow(() -> new PolicyAdminException("departmentId", "Choose an active department."));
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    private static String range(BigDecimal min, BigDecimal max) {
        return "INR " + min.toPlainString()
                + (max == null ? " and above" : " up to " + max.toPlainString());
    }

    private static String categoryLabel(RequestCategory category) {
        return category == null ? "any category" : category.getLabel();
    }

    private static String priorityLabel(RequestPriority priority) {
        return priority == null ? "any priority" : priority.getLabel();
    }

    private static String departmentName(Department department) {
        return department == null ? "any department" : department.getName();
    }

    private static String labels(Collection<Role> roles) {
        return roles.stream().map(Role::getLabel).collect(Collectors.joining(", "));
    }

    private static String describe(ApprovalPolicy policy) {
        return range(policy.getMinAmount(), policy.getMaxAmount())
                + "; " + categoryLabel(policy.getCategory())
                + "; " + priorityLabel(policy.getPriority())
                + "; " + departmentName(policy.getDepartment())
                + "; approvers: " + labels(policy.getApproverRolesInOrder())
                + "; " + (policy.isActive() ? "active" : "inactive");
    }

    private void audit(User actor, String action, ApprovalPolicy policy, String details) {
        auditService.record(actor, action, AuditService.ENTITY_POLICY, policy.getId(), details);
    }
}