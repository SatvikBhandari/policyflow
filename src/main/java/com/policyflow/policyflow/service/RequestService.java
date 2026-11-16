package com.policyflow.policyflow.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.ApprovalAction;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.ActionType;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.ApprovalActionRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;

@Service
public class RequestService {

    private static final int MAX_SUMMARY_LENGTH = 1000;
    private static final int MAX_REASON_LENGTH = 1000;

    private final RequestRepository requestRepository;
    private final UserRepository userRepository;
    private final PolicyEvaluationService policyEvaluationService;
    private final ApproverResolver approverResolver;
    private final ApprovalActionRepository actionRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public RequestService(RequestRepository requestRepository,
                          UserRepository userRepository,
                          PolicyEvaluationService policyEvaluationService,
                          ApproverResolver approverResolver,
                          ApprovalActionRepository actionRepository,
                          AuditService auditService,
                          NotificationService notificationService) {
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.policyEvaluationService = policyEvaluationService;
        this.approverResolver = approverResolver;
        this.actionRepository = actionRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    // ------------------------------------------------------------------ create and submit in one step

    /**
     * Creates a request owned by the given employee, submits it and routes it for approval.
     * Everything happens in one transaction: if any step fails, nothing is saved.
     *
     * @throws com.policyflow.policyflow.exception.NoEligibleApproverException
     *         if some stage of the approval chain would have nobody to decide it
     */
    @Transactional
    public Request createAndSubmit(RequestForm form, User employee) {
        Objects.requireNonNull(form, "form must not be null");
        User requester = loadRequester(employee);

        // Refuse before anything is saved if the chain cannot be staffed
        PolicyEvaluation evaluation = evaluateAndCheck(
                form.getAmount(), form.getCategory(), priorityOf(form), requester);

        Request request = createDraftRecord(form, requester);
        return completeSubmission(request, requester, evaluation);
    }

    // ------------------------------------------------------------------ drafts

    /**
     * Saves a draft. It gets its reference straight away but is not routed: no policy is evaluated,
     * no approver is checked and nobody is notified until it is submitted.
     */
    @Transactional
    public Request saveDraft(RequestForm form, User employee) {
        Objects.requireNonNull(form, "form must not be null");
        User requester = loadRequester(employee);
        return requestRepository.saveAndFlush(createDraftRecord(form, requester));
    }

    /** Changes the owner's draft. */
    @Transactional
    public Request updateDraft(Long requestId, RequestForm form, User employee) {
        Objects.requireNonNull(form, "form must not be null");
        User requester = loadRequester(employee);
        Request draft = findOwn(requestId, requester);
        requireDraft(draft);

        applyForm(draft, form);
        return requestRepository.saveAndFlush(draft);
    }

    /** Applies the edits and submits the draft in one step. If it cannot be submitted, nothing changes. */
    @Transactional
    public Request updateAndSubmitDraft(Long requestId, RequestForm form, User employee) {
        Objects.requireNonNull(form, "form must not be null");
        User requester = loadRequester(employee);
        Request draft = findOwn(requestId, requester);
        requireDraft(draft);

        PolicyEvaluation evaluation = evaluateAndCheck(
                form.getAmount(), form.getCategory(), priorityOf(form), requester);
        applyForm(draft, form);
        return completeSubmission(draft, requester, evaluation);
    }

    /**
     * Submits the owner's draft as it is. It is routed by the policies in force right now.
     *
     * @throws com.policyflow.policyflow.exception.NoEligibleApproverException if a stage would have nobody to decide it
     * @throws IllegalStateException if the request is not a draft (for example it was already submitted)
     */
    @Transactional
    public Request submitDraft(Long requestId, User employee) {
        User requester = loadRequester(employee);
        Request draft = findOwn(requestId, requester);
        requireDraft(draft);

        PolicyEvaluation evaluation = evaluateAndCheck(
                draft.getAmount(), draft.getCategory(), draft.getPriority(), requester);
        return completeSubmission(draft, requester, evaluation);
    }

    /** Deletes the owner's draft. Submitted requests can only be cancelled, never deleted. */
    @Transactional
    public void deleteDraft(Long requestId, User employee) {
        User owner = loadRequester(employee);
        Request draft = findOwn(requestId, owner);
        requireDraft(draft);

        Long draftId = draft.getId();
        String reference = draft.getReference();
        requestRepository.delete(draft);
        requestRepository.flush();

        auditService.record(owner, AuditService.REQUEST_DRAFT_DELETED, AuditService.ENTITY_REQUEST,
                draftId, reference + " (draft) deleted by " + owner.getFullName());
    }

    // ------------------------------------------------------------------ cancelling

    /**
     * Cancels a request on behalf of its owner. Allowed until the first approver has approved it.
     * Drafts are deleted, not cancelled.
     *
     * @throws ResourceNotFoundException if the request does not exist or belongs to someone else
     * @throws IllegalStateException     if approval has already started, the request is already closed, or it is a draft
     */
    @Transactional
    public Request cancel(Long requestId, User employee, String reason) {
        Objects.requireNonNull(employee, "employee must not be null");

        Request request = requestRepository.findByIdAndEmployeeId(requestId, employee.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        if (request.getStatus() == RequestStatus.DRAFT) {
            throw new IllegalStateException("A draft is deleted, not cancelled");
        }

        String cleaned = reason == null || reason.isBlank() ? null : reason.trim();
        if (cleaned != null && cleaned.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }

        // Remember whose step was pending: cancelling closes the stages
        Role waitingRole = request.getCurrentStage() == null ? null : request.getCurrentStage().getApproverRole();

        request.cancel(); // throws IllegalStateException when cancelling is no longer allowed

        User owner = request.getEmployee(); // the verified owner
        actionRepository.save(new ApprovalAction(request, null, owner, ActionType.CANCELLED, cleaned));
        auditService.record(owner, AuditService.REQUEST_CANCELLED, AuditService.ENTITY_REQUEST,
                request.getId(), request.getReference() + " cancelled by " + owner.getFullName());

        notificationService.onCancelled(request, waitingRole); // tell the approvers it was withdrawn

        return requestRepository.saveAndFlush(request);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<Request> findAllFor(User employee) {
        return requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId());
    }

    @Transactional(readOnly = true)
    public Request getFor(Long requestId, User employee) {
        return requestRepository.findByIdAndEmployeeId(requestId, employee.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));
    }

    // ------------------------------------------------------------------ helpers

    private User loadRequester(User employee) {
        Objects.requireNonNull(employee, "employee must not be null");
        // Work with the user as loaded inside this transaction
        return userRepository.findById(employee.getId())
                .orElseThrow(() -> new IllegalStateException("Requester no longer exists"));
    }

    /** The requester's own request, or "not found" when it does not exist or belongs to someone else. */
    private Request findOwn(Long requestId, User requester) {
        return requestRepository.findByIdAndEmployeeId(requestId, requester.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));
    }

    private static void requireDraft(Request request) {
        if (request.getStatus() != RequestStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only draft requests can be changed (current status: " + request.getStatus() + ")");
        }
    }

    private static RequestPriority priorityOf(RequestForm form) {
        return form.getPriority() == null ? RequestPriority.NORMAL : form.getPriority();
    }

    /** Which approvals does company policy require, and can every stage be staffed? */
    private PolicyEvaluation evaluateAndCheck(BigDecimal amount, RequestCategory category,
                                              RequestPriority priority, User requester) {
        PolicyEvaluation evaluation = policyEvaluationService.evaluate(
                amount, category, priority, requester.getDepartment());
        approverResolver.requireEligibleApprovers(evaluation.getApproverRoles(), requester);
        return evaluation;
    }

    /** Saves a new request as a draft with its reference assigned. */
    private Request createDraftRecord(RequestForm form, User requester) {
        Request request = new Request(
                form.getTitle().trim(),
                form.getDescription().trim(),
                form.getCategory(),
                form.getAmount(),
                requester);
        request.changePriority(priorityOf(form)); // allowed: the request is still a draft
        request = requestRepository.save(request); // first insert: the id is generated
        request.assignReference();
        return request;
    }

    private static void applyForm(Request draft, RequestForm form) {
        draft.updateDraft(form.getTitle(), form.getDescription(), form.getCategory(),
                form.getAmount(), priorityOf(form));
    }

    /** DRAFT to SUBMITTED to PENDING_APPROVAL, with audit entry and notifications. Shared by every way of submitting. */
    private Request completeSubmission(Request request, User requester, PolicyEvaluation evaluation) {
        request.submit();
        request.routeForApproval(evaluation.getApproverRoles(), summarize(evaluation));

        String chain = evaluation.getApproverRoles().stream().map(Role::getLabel).collect(Collectors.joining(", "));
        auditService.record(requester, AuditService.REQUEST_SUBMITTED, AuditService.ENTITY_REQUEST,
                request.getId(), request.getReference() + " submitted. Priority: "
                        + request.getPriority().getLabel() + ". Approval chain: " + chain);

        notificationService.onSubmitted(request); // tell the approvers of the first step

        return requestRepository.saveAndFlush(request);
    }

    /** A readable record of which rules decided the chain. */
    private static String summarize(PolicyEvaluation evaluation) {
        String policies = evaluation.getMatchedPolicies().stream()
                .map(p -> p.getName() + " (policy #" + p.getId()
                        + (p.getVersion() == null ? "" : ", v" + p.getVersion()) + ")")
                .collect(Collectors.joining("; "));

        String text = policies.isEmpty()
                ? evaluation.getReason()
                : evaluation.getReason() + " Matched: " + policies;

        return text.length() <= MAX_SUMMARY_LENGTH
                ? text
                : text.substring(0, MAX_SUMMARY_LENGTH - 1) + "\u2026";
    }
}