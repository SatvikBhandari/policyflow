package com.policyflow.policyflow.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.ApprovalViews.DecidedItem;
import com.policyflow.policyflow.dto.ApprovalViews.HistoryEntry;
import com.policyflow.policyflow.dto.ApprovalViews.QueueItem;
import com.policyflow.policyflow.dto.ApprovalViews.Review;
import com.policyflow.policyflow.entity.ApprovalAction;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.ActionType;
import com.policyflow.policyflow.enums.NotificationType;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.exception.InvalidDecisionException;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.ApprovalActionRepository;
import com.policyflow.policyflow.repository.NotificationRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;

@Service
public class ApprovalService {

    private static final int MAX_COMMENT_LENGTH = 1000;

    private final RequestRepository requestRepository;
    private final UserRepository userRepository;
    private final ApprovalActionRepository actionRepository;
    private final NotificationRepository notificationRepository;
    private final ApproverResolver approverResolver;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public ApprovalService(RequestRepository requestRepository,
                           UserRepository userRepository,
                           ApprovalActionRepository actionRepository,
                           NotificationRepository notificationRepository,
                           ApproverResolver approverResolver,
                           AuditService auditService,
                           NotificationService notificationService) {
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.actionRepository = actionRepository;
        this.notificationRepository = notificationRepository;
        this.approverResolver = approverResolver;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    // ------------------------------------------------------------------ queues and review

    /** Requests whose current stage is waiting for this approver, oldest first. */
    @Transactional(readOnly = true)
    public List<QueueItem> pendingFor(User approver) {
        User actor = loadActor(approver);
        if (!actor.isActive() || !actor.getRole().isApprover()) {
            return List.of();
        }
        List<Request> requests = ApproverResolver.isDepartmentScoped(actor.getRole())
                ? requestRepository.findAwaitingApprovalInDepartment(
                        actor.getRole(), actor.getId(), actor.getDepartment().getId())
                : requestRepository.findAwaitingApproval(actor.getRole(), actor.getId());
        return requests.stream().map(this::toQueueItem).toList();
    }

    /** The approver's ten most recent approvals and rejections. */
    @Transactional(readOnly = true)
    public List<DecidedItem> recentlyDecidedBy(User approver) {
        User actor = loadActor(approver);
        return actionRepository
                .findTop10ByActorIdAndActionTypeInOrderByIdDesc(
                        actor.getId(), List.of(ActionType.APPROVED, ActionType.REJECTED))
                .stream()
                .map(action -> new DecidedItem(
                        action.getRequest().getId(),
                        action.getRequest().getReference(),
                        action.getRequest().getTitle(),
                        action.getActionType().getLabel(),
                        action.getActionType().getBadgeClass(),
                        action.getComment(),
                        action.getCreatedAt(),
                        action.getRequest().getStatus().getLabel(),
                        action.getRequest().getStatus().getBadgeClass()))
                .toList();
    }

    /**
     * Opens a request for review. The approver can decide it while it awaits them. Otherwise they may
     * still open it read-only if they decided on it earlier, or were asked to decide it and still could
     * (for example a colleague got there first). Anyone else sees "not found".
     */
    @Transactional(readOnly = true)
    public Review getReview(Long requestId, User approver) {
        User actor = loadActor(approver);
        Request request = requestRepository.findDetailedById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));

        boolean canDecide = canDecide(request, actor);
        if (!canDecide && !mayViewReadOnly(request, actor)) {
            throw new ResourceNotFoundException("Request not found");
        }
        String note = canDecide ? null : outcomeNote(request, actor);
        return new Review(request, historyFor(requestId), canDecide, note);
    }

    /** The recorded decisions of a request, oldest first. The caller must already be allowed to see the request. */
    @Transactional(readOnly = true)
    public List<HistoryEntry> historyFor(Long requestId) {
        return actionRepository.findByRequestIdOrderByIdAsc(requestId).stream()
                .map(action -> new HistoryEntry(
                        action.getStepNumber(),
                        action.getActor().getFullName(),
                        action.getActorRole().getLabel(),
                        action.getActionType().getLabel(),
                        action.getActionType().getBadgeClass(),
                        action.getComment(),
                        action.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ decisions

    /** Approves the stage awaiting this approver. A comment is optional. */
    @Transactional
    public Request approve(Long requestId, User approver, String comment) {
        User actor = loadActor(approver);
        Request request = loadForDecision(requestId, actor);
        String cleaned = cleanComment(comment);

        RequestStage stage = request.approveCurrentStage();
        actionRepository.save(new ApprovalAction(request, stage, actor, ActionType.APPROVED, cleaned));

        auditService.record(actor, AuditService.STAGE_APPROVED, AuditService.ENTITY_REQUEST,
                request.getId(), describe(request, stage, "approved", actor));
        if (request.getStatus() == RequestStatus.APPROVED) {
            auditService.record(actor, AuditService.REQUEST_APPROVED, AuditService.ENTITY_REQUEST,
                    request.getId(), request.getReference() + " fully approved");
        }
        notificationService.onApproved(request, stage, actor);
        return requestRepository.saveAndFlush(request); // the stage version check happens here
    }

    /** Rejects the stage awaiting this approver and ends the workflow. A comment is required. */
    @Transactional
    public Request reject(Long requestId, User approver, String comment) {
        User actor = loadActor(approver);
        Request request = loadForDecision(requestId, actor);
        String cleaned = cleanComment(comment);
        if (cleaned == null) {
            throw new InvalidDecisionException("A comment is required when rejecting a request.");
        }

        RequestStage stage = request.rejectCurrentStage();
        actionRepository.save(new ApprovalAction(request, stage, actor, ActionType.REJECTED, cleaned));

        auditService.record(actor, AuditService.REQUEST_REJECTED, AuditService.ENTITY_REQUEST,
                request.getId(), describe(request, stage, "rejected", actor));
        notificationService.onRejected(request, stage, actor, cleaned);
        return requestRepository.saveAndFlush(request);
    }

    // ------------------------------------------------------------------ helpers

    private User loadActor(User approver) {
        Objects.requireNonNull(approver, "approver must not be null");
        return userRepository.findById(approver.getId())
                .orElseThrow(() -> new IllegalStateException("Approver no longer exists"));
    }

    /** True when the request's current stage can be decided by this person right now. */
    private boolean canDecide(Request request, User actor) {
        if (request.getStatus() != RequestStatus.PENDING_APPROVAL) {
            return false;
        }
        RequestStage current = request.getCurrentStage();
        return current != null
                && approverResolver.isEligibleFor(current.getApproverRole(), actor, request.getEmployee());
    }

    private Request loadForDecision(Long requestId, User actor) {
        Request request = requestRepository.findDetailedById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));

        if (canDecide(request, actor)) {
            return request;
        }
        if (mayViewReadOnly(request, actor)) {
            // A legitimate approver who lost the race (or clicked on a stale page): explain, do not hide
            throw new InvalidDecisionException(outcomeNote(request, actor) + " Your decision was not recorded.");
        }
        throw new ResourceNotFoundException("Request not found");
    }

    /** Decided on it earlier, or was asked to decide it and still could. */
    private boolean mayViewReadOnly(Request request, User actor) {
        if (actionRepository.existsByRequestIdAndActorId(request.getId(), actor.getId())) {
            return true;
        }
        return wasAskedToDecide(request, actor);
    }

    /**
     * Was told a decision was needed, and is still active and eligible for one of the request's stages.
     * Both conditions matter: the notification proves they were invited, and eligibility proves they
     * still belong to the right role and department (a deactivated or moved person loses access).
     */
    private boolean wasAskedToDecide(Request request, User actor) {
        if (!notificationRepository.existsByRecipientIdAndRequestIdAndType(
                actor.getId(), request.getId(), NotificationType.APPROVAL_REQUESTED)) {
            return false;
        }
        for (RequestStage stage : request.getStages()) {
            if (approverResolver.isEligibleFor(stage.getApproverRole(), actor, request.getEmployee())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A one-line explanation of what already happened, for someone who can no longer decide.
     * Prefers the decision taken at the viewer's own step, otherwise the latest action.
     */
    private String outcomeNote(Request request, User viewer) {
        List<ApprovalAction> actions = actionRepository.findByRequestIdOrderByIdAsc(request.getId());
        if (actions.isEmpty()) {
            return "This request is not awaiting your decision.";
        }

        ApprovalAction chosen = actions.get(actions.size() - 1);
        for (ApprovalAction action : actions) {
            boolean decision = action.getActionType() == ActionType.APPROVED
                    || action.getActionType() == ActionType.REJECTED;
            if (decision && action.getActorRole() == viewer.getRole()) {
                chosen = action; // the decision taken at the viewer's own step (the latest one wins)
            }
        }

        String who = chosen.getActor().getId().equals(viewer.getId())
                ? "you"
                : chosen.getActor().getFullName() + " (" + chosen.getActorRole().getLabel() + ")";
        StringBuilder note = new StringBuilder(request.getReference()).append(" was already ")
                .append(verbFor(chosen.getActionType())).append(" by ").append(who).append('.');

        RequestStage waiting = request.getCurrentStage();
        if (request.getStatus() == RequestStatus.PENDING_APPROVAL && waiting != null) {
            note.append(" It is now waiting for ").append(waiting.getApproverRole().getLabel()).append('.');
        }
        return note.toString();
    }

    private static String verbFor(ActionType type) {
        return switch (type) {
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
            case CANCELLED -> "cancelled";
            case FORCE_CLOSED -> "closed";
        };
    }

    private static String cleanComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new InvalidDecisionException("Comment must be at most " + MAX_COMMENT_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static String describe(Request request, RequestStage stage, String verb, User actor) {
        return request.getReference() + ": step " + stage.getStepNumber()
                + " (" + stage.getApproverRole().getLabel() + ") " + verb + " by " + actor.getFullName();
    }

    private QueueItem toQueueItem(Request request) {
        RequestStage current = request.getCurrentStage();
        return new QueueItem(
                request.getId(),
                request.getReference(),
                request.getTitle(),
                request.getCategory().getLabel(),
                request.getAmount(),
                request.getEmployee().getFullName(),
                request.getEmployee().getDepartment().getName(),
                request.getSubmittedAt(),
                current == null ? 0 : current.getStepNumber(),
                request.getStages().size(),
                request.getPriority());
    }
}