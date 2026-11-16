package com.policyflow.policyflow.support;

import java.math.BigDecimal;
import java.util.List;

import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.UserRepository;

/** Test helpers for building workflow scenarios. Use them inside @Transactional tests so everything is rolled back. */
public final class WorkflowFixtures {

    private WorkflowFixtures() {
    }

    /** Deactivates every existing policy so only policies created by the test apply. */
    public static void isolatePolicies(ApprovalPolicyRepository repository) {
        List<ApprovalPolicy> existing = repository.findAll();
        existing.forEach(policy -> policy.setActive(false));
        repository.saveAllAndFlush(existing);
    }

    /** An active policy that matches every request and requires the given approver roles. */
    public static ApprovalPolicy policy(ApprovalPolicyRepository repository, String name, Role... roles) {
        ApprovalPolicy policy = new ApprovalPolicy(name, BigDecimal.ZERO, null, null, null);
        for (Role role : roles) {
            policy.addApprover(role);
        }
        return repository.saveAndFlush(policy);
    }

    public static User user(UserRepository repository, String emailPrefix, Role role, Department department) {
        return repository.save(new User(
                role.getLabel() + " " + emailPrefix, emailPrefix + "@policyflow.local", role, department));
    }

    public static RequestForm form(String amount) {
        RequestForm form = new RequestForm();
        form.setTitle("Workflow test request");
        form.setCategory(RequestCategory.OTHER);
        form.setAmount(new BigDecimal(amount));
        form.setDescription("Created by an automated test.");
        return form;
    }
}