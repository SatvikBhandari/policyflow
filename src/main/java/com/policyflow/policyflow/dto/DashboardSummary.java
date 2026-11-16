package com.policyflow.policyflow.dto;

import java.math.BigDecimal;
import java.util.List;

import com.policyflow.policyflow.entity.Request;

public class DashboardSummary {

    private final long total;
    private final long drafts;
    private final long submitted;
    private final long pendingApproval;
    private final long approved;
    private final long rejected;
    private final long cancelled;
    private final BigDecimal totalAmount;
    private final long thisMonthCount;
    private final long lastMonthCount;
    private final List<Request> recent;
    private final List<CategoryTotal> categories;

    public DashboardSummary(long total, long drafts, long submitted, long pendingApproval,
                            long approved, long rejected, long cancelled,
                            BigDecimal totalAmount, long thisMonthCount, long lastMonthCount,
                            List<Request> recent, List<CategoryTotal> categories) {
        this.total = total;
        this.drafts = drafts;
        this.submitted = submitted;
        this.pendingApproval = pendingApproval;
        this.approved = approved;
        this.rejected = rejected;
        this.cancelled = cancelled;
        this.totalAmount = totalAmount;
        this.thisMonthCount = thisMonthCount;
        this.lastMonthCount = lastMonthCount;
        this.recent = recent;
        this.categories = categories;
    }

    public long getTotal() { return total; }
    public long getDrafts() { return drafts; }
    public long getSubmitted() { return submitted; }
    public long getPendingApproval() { return pendingApproval; }
    public long getApproved() { return approved; }
    public long getRejected() { return rejected; }
    public long getCancelled() { return cancelled; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public long getThisMonthCount() { return thisMonthCount; }
    public long getLastMonthCount() { return lastMonthCount; }
    public List<Request> getRecent() { return recent; }
    public List<CategoryTotal> getCategories() { return categories; }

    public long getMonthDelta() { return thisMonthCount - lastMonthCount; }
    public long getMonthDeltaAbs() { return Math.abs(getMonthDelta()); }

    /** Spend for one category, with its share of the total as a whole percentage. */
    public static class CategoryTotal {
        private final String label;
        private final BigDecimal amount;
        private final long count;
        private final int percent;

        public CategoryTotal(String label, BigDecimal amount, long count, int percent) {
            this.label = label;
            this.amount = amount;
            this.count = count;
            this.percent = percent;
        }

        public String getLabel() { return label; }
        public BigDecimal getAmount() { return amount; }
        public long getCount() { return count; }
        public int getPercent() { return percent; }
    }
}