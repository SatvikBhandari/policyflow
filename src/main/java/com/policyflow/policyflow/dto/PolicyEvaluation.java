package com.policyflow.policyflow.dto;

import java.util.List;

import com.policyflow.policyflow.enums.Role;

/** The outcome of evaluating the approval policies for one request. */
public class PolicyEvaluation {

    private final List<Role> approverRoles;
    private final List<MatchedPolicy> matchedPolicies;
    private final boolean fallbackUsed;
    private final String reason;

    public PolicyEvaluation(List<Role> approverRoles, List<MatchedPolicy> matchedPolicies,
                            boolean fallbackUsed, String reason) {
        this.approverRoles = List.copyOf(approverRoles);
        this.matchedPolicies = List.copyOf(matchedPolicies);
        this.fallbackUsed = fallbackUsed;
        this.reason = reason;
    }

    /** Roles that must approve, in approval order. Never empty. */
    public List<Role> getApproverRoles() { return approverRoles; }

    /** The policies that decided this chain (empty when the fallback was used because nothing matched). */
    public List<MatchedPolicy> getMatchedPolicies() { return matchedPolicies; }

    /** True when no usable policy matched and the safe default chain was applied. */
    public boolean isFallbackUsed() { return fallbackUsed; }

    /** Human-readable explanation, suitable for the audit trail. */
    public String getReason() { return reason; }

    /** A snapshot of one matching policy, so later edits cannot change the record. */
    public static class MatchedPolicy {
        private final Long id;
        private final String name;
        private final Long version;

        public MatchedPolicy(Long id, String name, Long version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public Long getVersion() { return version; }
    }
}