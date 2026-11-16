package com.policyflow.policyflow.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.PolicyAdminException;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.UserRepository;

/** Read-only view of ACTIVE policies for employees and approvers. */
@Service
public class PolicyViewService {

    private static final BigDecimal SMALLEST_AMOUNT = new BigDecimal("0.01");

    private final ApprovalPolicyRepository policyRepository;
    private final UserRepository userRepository;
    private final PolicyEvaluationService evaluationService;

    public PolicyViewService(ApprovalPolicyRepository policyRepository,
                             UserRepository userRepository,
                             PolicyEvaluationService evaluationService) {
        this.policyRepository = policyRepository;
        this.userRepository = userRepository;
        this.evaluationService = evaluationService;
    }

    @Transactional(readOnly = true)
    public List<ApprovalPolicy> activePolicies(User viewer) {
        requireNonAdmin(viewer);
        return policyRepository.findAllWithDepartment().stream()
                .filter(ApprovalPolicy::isActive)
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyEvaluation preview(User viewer, BigDecimal amount, RequestCategory category) {
        User actor = requireNonAdmin(viewer);
        if (amount == null || amount.compareTo(SMALLEST_AMOUNT) < 0) {
            throw new PolicyAdminException("amount", "Enter an amount of at least 0.01.");
        }
        if (category == null) {
            throw new PolicyAdminException("category", "Choose a category.");
        }
        Department department = actor.getDepartment();
        return evaluationService.evaluate(amount, category, department);
    }

    private User requireNonAdmin(User viewer) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        User actor = userRepository.findById(viewer.getId())
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));
        if (!actor.isActive() || actor.getRole() == Role.ADMIN) {
            throw new AccessDeniedException("Policy view is for employees and approvers");
        }
        return actor;
    }
}