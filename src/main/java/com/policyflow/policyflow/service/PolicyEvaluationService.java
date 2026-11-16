package com.policyflow.policyflow.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.dto.PolicyEvaluation.MatchedPolicy;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;

/**
 * Decides which approvals a request needs.
 * Every active policy whose conditions match adds its approver roles; the request needs all of them.
 * If nothing usable matches, the request goes to the Department Head: it is never auto-approved.
 */
@Service
public class PolicyEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyEvaluationService.class);

    /** The safe default chain when no usable policy matches. */
    static final List<Role> FALLBACK_ROLES = List.of(Role.DEPARTMENT_HEAD);

    private final ApprovalPolicyRepository policyRepository;

    public PolicyEvaluationService(ApprovalPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    /** Evaluates a request of normal priority. Kept for callers that do not deal with priority. */
    @Transactional(readOnly = true)
    public PolicyEvaluation evaluate(BigDecimal amount, RequestCategory category, Department department) {
        return evaluate(amount, category, RequestPriority.NORMAL, department);
    }

    /**
     * @param department the requester's department (policies can be limited to one department)
     */
    @Transactional(readOnly = true)
    public PolicyEvaluation evaluate(BigDecimal amount, RequestCategory category,
                                     RequestPriority priority, Department department) {
        return evaluate(amount, category, priority, department, policyRepository.findByActiveTrue());
    }

    /** Pure decision logic for a request of normal priority (no database access). */
    PolicyEvaluation evaluate(BigDecimal amount, RequestCategory category,
                              Department department, List<ApprovalPolicy> policies) {
        return evaluate(amount, category, RequestPriority.NORMAL, department, policies);
    }

    /** Pure decision logic: no database access, so it is easy to unit test. A null priority counts as NORMAL. */
    PolicyEvaluation evaluate(BigDecimal amount, RequestCategory category, RequestPriority priority,
                              Department department, List<ApprovalPolicy> policies) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (category == null) {
            throw new IllegalArgumentException("Category is required");
        }
        RequestPriority effectivePriority = priority == null ? RequestPriority.NORMAL : priority;

        EnumSet<Role> required = EnumSet.noneOf(Role.class); // iterates in approval order
        List<MatchedPolicy> matched = new ArrayList<>();

        for (ApprovalPolicy policy : policies) {
            if (!matches(policy, amount, category, effectivePriority, department)) {
                continue;
            }
            matched.add(new MatchedPolicy(policy.getId(), policy.getName(), policy.getVersion()));
            for (Role role : policy.getApproverRoles()) {
                if (role.isApprover()) { // ignore anything that should never be an approver
                    required.add(role);
                }
            }
        }

        if (required.isEmpty()) {
            String why = matched.isEmpty()
                    ? "No active policy matched this request."
                    : "The matching policies define no approvers.";
            log.warn("Policy fallback used for category {}: {}", category, why);
            return new PolicyEvaluation(FALLBACK_ROLES, matched, true,
                    why + " Routed to the Department Head for a manual decision.");
        }

        return new PolicyEvaluation(new ArrayList<>(required), matched, false,
                "Approvers required by " + matched.size()
                        + (matched.size() == 1 ? " matching policy." : " matching policies."));
    }

    /** Matching for a request of normal priority. */
    static boolean matches(ApprovalPolicy policy, BigDecimal amount,
                           RequestCategory category, Department department) {
        return matches(policy, amount, category, RequestPriority.NORMAL, department);
    }

    /** Amount range is [min, max): the minimum is included, the maximum is not. A null condition means "any". */
    static boolean matches(ApprovalPolicy policy, BigDecimal amount, RequestCategory category,
                           RequestPriority priority, Department department) {
        if (!policy.isActive()) {
            return false;
        }
        if (amount.compareTo(policy.getMinAmount()) < 0) {
            return false;
        }
        if (policy.getMaxAmount() != null && amount.compareTo(policy.getMaxAmount()) >= 0) {
            return false;
        }
        if (policy.getCategory() != null && policy.getCategory() != category) {
            return false;
        }
        if (policy.getPriority() != null && policy.getPriority() != priority) {
            return false;
        }
        if (policy.getDepartment() != null) {
            Long wanted = policy.getDepartment().getId();
            if (department == null || wanted == null || !Objects.equals(wanted, department.getId())) {
                return false;
            }
        }
        return true;
    }
}