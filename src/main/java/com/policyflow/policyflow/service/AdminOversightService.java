package com.policyflow.policyflow.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.AdminViews.Overview;
import com.policyflow.policyflow.dto.AdminViews.RoleCount;
import com.policyflow.policyflow.dto.AdminViews.RoleUsers;
import com.policyflow.policyflow.dto.AdminViews.StatusCount;
import com.policyflow.policyflow.dto.AdminViews.StuckRequest;
import com.policyflow.policyflow.entity.ApprovalAction;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.ActionType;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.InvalidDecisionException;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.ApprovalActionRepository;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;

/** System overview and stuck-request tools for administrators. */
@Service
public class AdminOversightService {

    private static final int MAX_REASON_LENGTH = 1000;

    private final RequestRepository requestRepository;
    private final UserRepository userRepository;
    private final ApprovalPolicyRepository policyRepository;
    private final ApprovalActionRepository actionRepository;
    private final ApproverResolver approverResolver;
    private final AuditService auditService;
    private final AuditLogService auditLogService;
    private final AdminGuard adminGuard;
    private final NotificationService notificationService;

    public AdminOversightService(RequestRepository requestRepository,
                                 UserRepository userRepository,
                                 ApprovalPolicyRepository policyRepository,
                                 ApprovalActionRepository actionRepository,
                                 ApproverResolver approverResolver,
                                 AuditService auditService,
                                 AuditLogService auditLogService,
                                 AdminGuard adminGuard,
                                 NotificationService notificationService) {
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.policyRepository = policyRepository;
        this.actionRepository = actionRepository;
        this.approverResolver = approverResolver;
        this.auditService = auditService;
        this.auditLogService = auditLogService;
        this.adminGuard = adminGuard;
        this.notificationService = notificationService;
    }

    // ------------------------------------------------------------------ overview

    @Transactional(readOnly = true)
    public Overview overview(User admin) {
        adminGuard.requireAdmin(admin);

        long total = requestRepository.count();
        List<StatusCount> statusCounts = new ArrayList<>();
        for (RequestStatus status : RequestStatus.values()) {
            long count = requestRepository.countByStatus(status);
            statusCounts.add(new StatusCount(status.getLabel(), status.getBadgeClass(), count, percent(count, total)));
        }

        List<Request> pending = requestRepository.findAllByStatusDetailed(RequestStatus.PENDING_APPROVAL);
        Map<Role, Long> waitingByRole = countByCurrentRole(pending);
        List<RoleCount> pendingByRole = new ArrayList<>();
        for (Role role : Role.values()) {
            if (role.isApprover()) {
                pendingByRole.add(new RoleCount(role.getLabel(), waitingByRole.getOrDefault(role, 0L)));
            }
        }
        long stuck = pending.stream().filter(this::isStuck).count();

        BigDecimal approvedValue = requestRepository.sumAmountByStatus(RequestStatus.APPROVED);
        if (approvedValue == null) {
            approvedValue = BigDecimal.ZERO;
        }

        List<RoleUsers> usersByRole = new ArrayList<>();
        for (Role role : Role.values()) {
            usersByRole.add(new RoleUsers(role.getLabel(),
                    userRepository.countByRoleAndActiveTrue(role), userRepository.countByRole(role)));
        }

        return new Overview(total, pending.size(), stuck, approvedValue,
                userRepository.count(), userRepository.countByActiveTrue(),
                policyRepository.findByActiveTrue().size(), policyRepository.count(),
                statusCounts, pendingByRole, usersByRole, auditLogService.recent(admin));
    }

    /** How many pending requests are currently waiting for each approver role. */
    static Map<Role, Long> countByCurrentRole(List<Request> pending) {
        Map<Role, Long> counts = new EnumMap<>(Role.class);
        for (Request request : pending) {
            RequestStage current = request.getCurrentStage();
            if (current != null) {
                counts.merge(current.getApproverRole(), 1L, Long::sum);
            }
        }
        return counts;
    }

    static int percent(long part, long whole) {
        return whole <= 0 ? 0 : (int) Math.round(part * 100.0 / whole);
    }

    // ------------------------------------------------------------------ stuck requests

    /** Pending requests whose current stage has no eligible approver, oldest first. */
    @Transactional(readOnly = true)
    public List<StuckRequest> findStuck(User admin) {
        adminGuard.requireAdmin(admin);
        List<StuckRequest> stuck = new ArrayList<>();
        for (Request request : requestRepository.findAllByStatusDetailed(RequestStatus.PENDING_APPROVAL)) {
            RequestStage current = request.getCurrentStage();
            if (current != null && !hasEligibleApprover(current, request)) {
                stuck.add(toStuckView(request, current));
            }
        }
        return stuck;
    }

    /**
     * Ends a stuck request. The request is re-checked first: it must still be pending and its
     * current stage must still have nobody who may decide it. The reason is mandatory and becomes
     * part of the request's approval history and the audit log.
     */
    @Transactional
    public Request forceClose(User admin, Long requestId, String reason) {
        User actor = adminGuard.requireAdmin(admin);
        String cleaned = cleanReason(reason);

        Request request = requestRepository.findDetailedById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));

        if (request.getStatus() != RequestStatus.PENDING_APPROVAL) {
            throw new InvalidDecisionException(
                    "This request is no longer pending approval, so it cannot be closed.");
        }
        RequestStage current = request.getCurrentStage();
        if (current == null || hasEligibleApprover(current, request)) {
            throw new InvalidDecisionException("This request is not stuck: an eligible approver exists. "
                    + "Ask them to decide it, or make sure they can sign in.");
        }

        request.forceClose();
        actionRepository.save(new ApprovalAction(request, current, actor, ActionType.FORCE_CLOSED, cleaned));
        auditService.record(actor, AuditService.REQUEST_FORCE_CLOSED, AuditService.ENTITY_REQUEST,
                request.getId(),
                request.getReference() + " closed by administrator " + actor.getFullName()
                        + " at step " + current.getStepNumber() + " (" + current.getApproverRole().getLabel()
                        + "). Reason: " + cleaned);
        notificationService.onForceClosed(request, actor, cleaned);
        return requestRepository.saveAndFlush(request); // the stage version check happens here
    }

    // ------------------------------------------------------------------ helpers

    private boolean isStuck(Request request) {
        RequestStage current = request.getCurrentStage();
        return current != null && !hasEligibleApprover(current, request);
    }

    private boolean hasEligibleApprover(RequestStage stage, Request request) {
        return approverResolver.hasEligibleApprover(stage.getApproverRole(), request.getEmployee());
    }

    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidDecisionException("A reason is required to close a request.");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new InvalidDecisionException("The reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        return trimmed;
    }

    private StuckRequest toStuckView(Request request, RequestStage current) {
        User requester = request.getEmployee();
        Role role = current.getApproverRole();
        String explanation = ApproverResolver.isDepartmentScoped(role)
                ? "Step " + current.getStepNumber() + " needs a " + role.getLabel() + " from "
                        + requester.getDepartment().getName() + ", but no active " + role.getLabel()
                        + " other than the requester exists there."
                : "Step " + current.getStepNumber() + " needs a " + role.getLabel()
                        + ", but no active " + role.getLabel() + " other than the requester exists.";

        return new StuckRequest(request.getId(), request.getReference(), request.getTitle(),
                request.getCategory().getLabel(), request.getAmount(), requester.getFullName(),
                requester.getDepartment().getName(), request.getSubmittedAt(),
                current.getStepNumber(), request.getStages().size(), role.getLabel(), explanation);
    }
}