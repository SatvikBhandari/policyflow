package com.policyflow.policyflow.dto;

import java.math.BigDecimal;
import java.util.List;

/** Which request amounts are covered by active "general" policies (no category or department condition). */
public class PolicyCoverage {

    private final boolean generalPolicyPresent;
    private final List<Gap> gaps;

    public PolicyCoverage(boolean generalPolicyPresent, List<Gap> gaps) {
        this.generalPolicyPresent = generalPolicyPresent;
        this.gaps = List.copyOf(gaps);
    }

    /** True when at least one active general policy with an approver exists. */
    public boolean isGeneralPolicyPresent() { return generalPolicyPresent; }

    public List<Gap> getGaps() { return gaps; }

    public boolean isHealthy() { return generalPolicyPresent && gaps.isEmpty(); }

    /** A range of amounts [from, to) that no general policy covers. A null "to" means "and above". */
    public static class Gap {
        private final BigDecimal from;
        private final BigDecimal to;

        public Gap(BigDecimal from, BigDecimal to) {
            this.from = from;
            this.to = to;
        }

        public BigDecimal getFrom() { return from; }
        public BigDecimal getTo() { return to; }
        public boolean isOpenEnded() { return to == null; }
    }
}