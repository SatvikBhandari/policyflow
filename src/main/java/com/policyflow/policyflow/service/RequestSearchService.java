package com.policyflow.policyflow.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.util.SearchText;

/** Search, filter and pagination over one employee's own requests. */
@Service
public class RequestSearchService {

    public static final int PAGE_SIZE = 10;

    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2999, 1, 1);

    private final RequestRepository requestRepository;

    public RequestSearchService(RequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    /**
     * The employee's own requests, newest first. Null or blank filters mean "any"; both dates are inclusive
     * and refer to the day the request was created. The query is always scoped to the given employee.
     *
     * @throws IllegalArgumentException if the start date is after the end date
     */
    @Transactional(readOnly = true)
    public Page<Request> searchOwn(User employee, String text, RequestStatus status, RequestCategory category,
                                   LocalDate from, LocalDate to, int page) {
        Objects.requireNonNull(employee, "employee must not be null");
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("The start date must not be after the end date.");
        }
        LocalDateTime fromTime = (from == null ? EARLIEST : from).atStartOfDay();
        LocalDateTime toTime = (to == null ? LATEST : to).plusDays(1).atStartOfDay();

        return requestRepository.searchOwn(
                employee.getId(),
                status == null ? 0 : 1, status == null ? RequestStatus.DRAFT : status,
                category == null ? 0 : 1, category == null ? RequestCategory.OTHER : category,
                SearchText.likePattern(text),
                fromTime, toTime,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }
}