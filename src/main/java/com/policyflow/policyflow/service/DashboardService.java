package com.policyflow.policyflow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.DashboardSummary;
import com.policyflow.policyflow.dto.DashboardSummary.CategoryTotal;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.repository.RequestRepository;

@Service
public class DashboardService {

    private static final int RECENT_LIMIT = 5;

    private final RequestRepository requestRepository;

    public DashboardService(RequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summaryFor(User employee) {
        List<Request> requests = requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId());
        return summarize(requests, LocalDate.now());
    }

    /** Pure calculation: no database access, so it is easy to unit test. Newest request first. */
    DashboardSummary summarize(List<Request> requests, LocalDate today) {
        YearMonth thisMonth = YearMonth.from(today);
        YearMonth lastMonth = thisMonth.minusMonths(1);

        long drafts = 0;
        long submitted = 0;
        long pendingApproval = 0;
        long approved = 0;
        long rejected = 0;
        long cancelled = 0;
        long thisMonthCount = 0;
        long lastMonthCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<RequestCategory, BigDecimal> amountByCategory = new EnumMap<>(RequestCategory.class);
        Map<RequestCategory, Long> countByCategory = new EnumMap<>(RequestCategory.class);

        for (Request request : requests) {
            switch (request.getStatus()) {
                case DRAFT -> drafts++;
                case SUBMITTED -> submitted++;
                case PENDING_APPROVAL -> pendingApproval++;
                case APPROVED -> approved++;
                case REJECTED -> rejected++;
                case CANCELLED -> cancelled++;
            }
            if (request.getStatus() == RequestStatus.CANCELLED) {
                continue; // cancelled requests do not count towards value, categories or months
            }

            totalAmount = totalAmount.add(request.getAmount());
            amountByCategory.merge(request.getCategory(), request.getAmount(), BigDecimal::add);
            countByCategory.merge(request.getCategory(), 1L, Long::sum);

            YearMonth created = YearMonth.from(request.getCreatedAt());
            if (created.equals(thisMonth)) {
                thisMonthCount++;
            } else if (created.equals(lastMonth)) {
                lastMonthCount++;
            }
        }

        List<CategoryTotal> categories = new ArrayList<>();
        for (Map.Entry<RequestCategory, BigDecimal> entry : amountByCategory.entrySet()) {
            int percent = totalAmount.signum() == 0 ? 0
                    : entry.getValue().multiply(BigDecimal.valueOf(100))
                            .divide(totalAmount, 0, RoundingMode.HALF_UP).intValue();
            categories.add(new CategoryTotal(entry.getKey().getLabel(), entry.getValue(),
                    countByCategory.get(entry.getKey()), percent));
        }
        categories.sort(Comparator.comparing(CategoryTotal::getAmount).reversed());

        List<Request> recent = requests.stream().limit(RECENT_LIMIT).toList();

        return new DashboardSummary(requests.size() - cancelled, drafts, submitted, pendingApproval,
                approved, rejected, cancelled, totalAmount,
                thisMonthCount, lastMonthCount, recent, categories);
    }
}