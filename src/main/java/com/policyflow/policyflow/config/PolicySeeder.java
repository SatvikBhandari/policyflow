package com.policyflow.policyflow.config;

import java.math.BigDecimal;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;

/**
 * DEVELOPMENT ONLY. Creates sample approval policies when none exist yet.
 * Amount ranges include the minimum and exclude the maximum.
 */
@Component
@Profile("!prod")
public class PolicySeeder implements CommandLineRunner {

    private final ApprovalPolicyRepository policyRepository;

    public PolicySeeder(ApprovalPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (policyRepository.count() > 0) {
            return; // never overwrite or duplicate existing policies
        }

        save("Low value: manager only",
                "Requests below INR 10,000 need manager approval.",
                "0.00", "10000.00", null, Role.MANAGER);

        save("Mid value: manager and finance",
                "Requests from INR 10,000 up to (not including) INR 50,000 need manager and finance approval.",
                "10000.00", "50000.00", null, Role.MANAGER, Role.FINANCE_OFFICER);

        save("High value: manager, finance and department head",
                "Requests of INR 50,000 and above need manager, finance and department head approval.",
                "50000.00", null, null, Role.MANAGER, Role.FINANCE_OFFICER, Role.DEPARTMENT_HEAD);

        save("Software purchases: finance review",
                "Software requests always need finance review, whatever the amount.",
                "0.00", null, RequestCategory.SOFTWARE, Role.FINANCE_OFFICER);
    }

    private void save(String name, String description, String min, String max,
                      RequestCategory category, Role... approvers) {
        ApprovalPolicy policy = new ApprovalPolicy(
                name, new BigDecimal(min), max == null ? null : new BigDecimal(max), category, null);
        policy.setDescription(description);
        for (Role approver : approvers) {
            policy.addApprover(approver);
        }
        policyRepository.save(policy);
    }
}