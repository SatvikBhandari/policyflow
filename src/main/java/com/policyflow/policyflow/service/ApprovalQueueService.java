package com.policyflow.policyflow.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.ApprovalViews.QueueItem;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;

/** Search, filter and pagination for an approver's queue. Who may see what is decided by ApprovalService. */
@Service
public class ApprovalQueueService {

    public static final int PAGE_SIZE = 20;

    private final ApprovalService approvalService;

    public ApprovalQueueService(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /** The approver's queue (oldest first), filtered by text and category, one page at a time. */
    @Transactional(readOnly = true)
    public Page<QueueItem> search(User approver, String text, RequestCategory category, int page) {
        List<QueueItem> matching = approvalService.pendingFor(approver).stream()
                .filter(item -> matches(item, text, category))
                .sorted(Comparator.comparingInt((QueueItem item) -> item.getPriority().ordinal()).reversed())
                .toList();

        int pageNumber = Math.max(page, 0);
        int start = (int) Math.min((long) pageNumber * PAGE_SIZE, matching.size());
        int end = Math.min(start + PAGE_SIZE, matching.size());
        return new PageImpl<>(List.copyOf(matching.subList(start, end)),
                PageRequest.of(pageNumber, PAGE_SIZE), matching.size());
    }

    /** Category must match when given; text must appear in the reference, title, requester or department. */
    static boolean matches(QueueItem item, String text, RequestCategory category) {
        if (category != null && !category.getLabel().equals(item.getCategoryLabel())) {
            return false;
        }
        if (text == null || text.isBlank()) {
            return true;
        }
        String needle = text.trim().toLowerCase(Locale.ROOT);
        return contains(item.getReference(), needle)
                || contains(item.getTitle(), needle)
                || contains(item.getRequesterName(), needle)
                || contains(item.getDepartmentName(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}