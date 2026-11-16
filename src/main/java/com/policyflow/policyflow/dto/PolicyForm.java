package com.policyflow.policyflow.dto;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.Role;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class PolicyForm {

    /** The policy version this form was opened with (edit only). Used to detect conflicting edits. */
    private Long version;

    @NotBlank(message = "Policy name is required")
    @Size(max = 120, message = "Policy name must be at most 120 characters")
    private String name;

    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    @NotNull(message = "Minimum amount is required")
    @DecimalMin(value = "0.00", message = "Minimum amount cannot be negative")
    @Digits(integer = 13, fraction = 2, message = "Use at most 13 digits and 2 decimal places")
    private BigDecimal minAmount;

    /** Optional. Empty means "no upper limit". */
    @DecimalMin(value = "0.01", message = "Maximum amount must be greater than zero")
    @Digits(integer = 13, fraction = 2, message = "Use at most 13 digits and 2 decimal places")
    private BigDecimal maxAmount;

    /** Optional. Empty means "any category". */
    private RequestCategory category;

    /** Optional. Empty means "any priority". */
    private RequestPriority priority;

    /** Optional. Empty means "any department". */
    private Long departmentId;

    @NotEmpty(message = "Choose at least one approver")
    private Set<Role> approverRoles = new LinkedHashSet<>();

    /** Whether a new policy applies immediately (create only). */
    private boolean active = true;

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }

    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; }

    public RequestCategory getCategory() { return category; }

    public RequestPriority getPriority() { return priority; }
    public void setPriority(RequestPriority priority) { this.priority = priority; }
    public void setCategory(RequestCategory category) { this.category = category; }

    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }

    public Set<Role> getApproverRoles() { return approverRoles; }
    public void setApproverRoles(Set<Role> approverRoles) { this.approverRoles = approverRoles; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}