package com.policyflow.policyflow.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.NoEligibleApproverException;
import com.policyflow.policyflow.repository.UserRepository;

/**
 * Who may approve a stage for a given requester:
 * an active user with the stage's role who is not the requester themselves.
 * Manager and Department Head must belong to the requester's department; Finance Officers can be from any.
 */
@Component
public class ApproverResolver {

    private final UserRepository userRepository;

    public ApproverResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public static boolean isDepartmentScoped(Role role) {
        return role == Role.MANAGER || role == Role.DEPARTMENT_HEAD;
    }

    /** May this approver decide a stage for this role, on a request raised by this requester? */
    public boolean isEligibleFor(Role stageRole, User approver, User requester) {
        if (approver == null || !approver.isActive()) {
            return false;
        }
        if (approver.getRole() != stageRole) {
            return false;
        }
        if (Objects.equals(approver.getId(), requester.getId())) {
            return false; // nobody approves their own request
        }
        if (isDepartmentScoped(stageRole)) {
            return Objects.equals(approver.getDepartment().getId(), requester.getDepartment().getId());
        }
        return true;
    }

    @Transactional(readOnly = true)
    public boolean hasEligibleApprover(Role role, User requester) {
        if (isDepartmentScoped(role)) {
            Long departmentId = requester.getDepartment().getId();
            return userRepository.existsByRoleAndDepartmentIdAndActiveTrueAndIdNot(
                    role, departmentId, requester.getId());
        }
        return userRepository.existsByRoleAndActiveTrueAndIdNot(role, requester.getId());
    }

    /** Everybody who may decide a stage with this role for this requester (same rules as isEligibleFor). */
    @Transactional(readOnly = true)
    public List<User> eligibleApprovers(Role role, User requester) {
        if (isDepartmentScoped(role)) {
            return userRepository.findByRoleAndDepartmentIdAndActiveTrueAndIdNot(
                    role, requester.getDepartment().getId(), requester.getId());
        }
        return userRepository.findByRoleAndActiveTrueAndIdNot(role, requester.getId());
    }

    /** Throws if any stage of the chain would have nobody to decide it. */
    @Transactional(readOnly = true)
    public void requireEligibleApprovers(List<Role> roles, User requester) {
        for (Role role : roles) {
            if (!hasEligibleApprover(role, requester)) {
                throw new NoEligibleApproverException(role);
            }
        }
    }
}