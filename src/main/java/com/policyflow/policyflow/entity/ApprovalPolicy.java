package com.policyflow.policyflow.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.hibernate.annotations.Check;

import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.Role;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "approval_policies")
@Check(constraints = "min_amount >= 0 and (max_amount is null or max_amount > min_amount)")
public class ApprovalPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    /** Inclusive lower bound of the amount range. */
    @Column(name = "min_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal minAmount;

    /** Exclusive upper bound of the amount range. Null means "no upper limit". */
    @Column(name = "max_amount", precision = 15, scale = 2)
    private BigDecimal maxAmount;

    /** Null means "any category". */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RequestCategory category;

    /** Null means "any priority". */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RequestPriority priority;

    /** The requester's department. Null means "any department". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /** Roles that must approve when this policy matches. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "approval_policy_approvers", joinColumns = @JoinColumn(name = "policy_id"))
    @Column(name = "approver_role", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Set<Role> approverRoles = EnumSet.noneOf(Role.class);

    @Column(nullable = false)
    private boolean active = true;

    /** Optimistic locking: a concurrent edit fails instead of silently overwriting. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ApprovalPolicy() {
        // required by JPA
    }

    public ApprovalPolicy(String name, BigDecimal minAmount, BigDecimal maxAmount,
                          RequestCategory category, Department department) {
        validate(name, minAmount, maxAmount);
        this.name = name.trim();
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.category = category;
        this.department = department;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Changes the policy's conditions. Applies the same rules as the constructor. */
    public void update(String name, String description, BigDecimal minAmount, BigDecimal maxAmount,
                       RequestCategory category, Department department) {
        validate(name, minAmount, maxAmount);
        this.name = name.trim();
        this.description = description;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.category = category;
        this.department = department;
    }

    private static void validate(String name, BigDecimal minAmount, BigDecimal maxAmount) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Policy name is required");
        }
        if (minAmount == null || minAmount.signum() < 0) {
            throw new IllegalArgumentException("Minimum amount must be zero or more");
        }
        if (maxAmount != null && maxAmount.compareTo(minAmount) <= 0) {
            throw new IllegalArgumentException("Maximum amount must be greater than the minimum amount");
        }
    }

    /** Adds a required approver. Only MANAGER, FINANCE_OFFICER and DEPARTMENT_HEAD are allowed. */
    public void addApprover(Role role) {
        if (role == null || !role.isApprover()) {
            throw new IllegalArgumentException("Role cannot approve requests: " + role);
        }
        approverRoles.add(role);
    }

    /** Replaces all approvers. Every role is checked first, so a bad role changes nothing. */
    public void replaceApprovers(Collection<Role> roles) {
        if (roles == null) {
            throw new IllegalArgumentException("Approver roles are required");
        }
        EnumSet<Role> replacement = EnumSet.noneOf(Role.class);
        for (Role role : roles) {
            if (role == null || !role.isApprover()) {
                throw new IllegalArgumentException("Role cannot approve requests: " + role);
            }
            replacement.add(role);
        }
        approverRoles.clear();
        approverRoles.addAll(replacement);
    }

    /** Read-only view. Use addApprover or replaceApprovers to change it, so every role is validated. */
    public Set<Role> getApproverRoles() {
        return Collections.unmodifiableSet(approverRoles);
    }

    /** Approver roles in approval order (Manager, Finance Officer, Department Head). */
    public List<Role> getApproverRolesInOrder() {
        EnumSet<Role> ordered = EnumSet.noneOf(Role.class);
        ordered.addAll(approverRoles);
        return new ArrayList<>(ordered);
    }

    public Long getId() { return id; }

    public String getName() { return name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getMinAmount() { return minAmount; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public RequestCategory getCategory() { return category; }

    public RequestPriority getPriority() { return priority; }

    /** Limits the policy to requests of this priority. Null means "any priority". */
    public void setPriority(RequestPriority priority) { this.priority = priority; }
    public Department getDepartment() { return department; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}